package vn.tramtruyen.reader.engine.speech

import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import com.android.dx.Code
import com.android.dx.DexMaker
import com.android.dx.Local
import com.android.dx.TypeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import vn.tramtruyen.tts.Korva
import vn.tramtruyen.tts.Piper
import vn.tramtruyen.tts.Supertonic
import vn.tramtruyen.tts.ViFast
import vn.tramtruyen.tts.VieNeu
import vn.tramtruyen.tts.VieNeuNano
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.WeakHashMap
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VBookModel(
    val id: String,
    val name: String,
    val language: String,
    val sizeBytes: Long,
    val description: String,
    val checksum: String,
    val downloadUrl: String,
    val speakerCount: Int,
    val gender: String,
    val modelType: String,
    val format: String,
    val sampleRate: Int,
    val previewUrl: String? = null,
    val catalogSpeakerNames: List<String> = emptyList(),
)

data class VBookModelRow(
    val model: VBookModel,
    val installed: Boolean,
    val voiceCount: Int,
    val progress: Float? = null,
)

data class VBookTtsUiState(
    val models: List<VBookModelRow> = emptyList(),
    val loadingCatalog: Boolean = true,
    val runtimeReady: Boolean = false,
    val message: String? = null,
)

/** Downloads VBook's public model catalog and runs the speech engines packaged with NovelBook. */
object VBookTtsRuntime {
    private const val CATALOG_URL = "https://raw.githubusercontent.com/Darkrai9x/vbook-settings/main/ai-tts/models.json"
    private const val COMMON_DATA_URL = "https://raw.githubusercontent.com/Darkrai9x/vbook-settings/main/ai-tts/data.zip"
    private const val DATA_VERSION = 3
    private const val RUNTIME_ASSET = "voice-runtime/vbook-1.2.7-c9117dfe"
    private const val MODEL_CATALOG_ASSET = "reader_catalog/vbook-models.json"
    private val engines = listOf(
        Piper.ENGINE, ViFast.ENGINE, VieNeu.ENGINE, VieNeuNano.ENGINE, Supertonic.ENGINE, Korva.ENGINE,
    ).associateBy { it.id() }
    private val requiredNativeLibraries = engines.values.flatMap { it.libraries() }.toSet()
    private val mutableState = MutableStateFlow(VBookTtsUiState())
    val state: StateFlow<VBookTtsUiState> = mutableState.asStateFlow()
    private val engineLock = kotlinx.coroutines.sync.Mutex()
    private val bridgeIds = AtomicInteger()
    private val bridgeClasses = WeakHashMap<ClassLoader, Class<*>>()
    @Volatile private var appContext: Context? = null
    @Volatile private var runtimeHandle: RuntimeHandle? = null
    private val bundledLibraries by lazy {
        val info = appContext?.applicationInfo ?: return@lazy emptySet()
        listOfNotNull(info.sourceDir, *info.splitSourceDirs.orEmpty()).flatMap { apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    requiredNativeLibraries.filter { zip.getEntry("lib/arm64-v8a/$it") != null }
                }
            }.getOrDefault(emptyList())
        }.toSet()
    }
    private var activeEngine: LoadedEngine? = null
    private val installedIds = LinkedHashSet<String>()

    private data class RuntimeHandle(val loader: DexClassLoader)
    private data class LoadedEngine(
        val modelId: String,
        val runtime: RuntimeHandle,
        val instance: Any,
        val modelRate: Int,
        val language: String,
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (mutableState.value.models.isEmpty()) {
            runCatching { context.assets.open(MODEL_CATALOG_ASSET).bufferedReader().use { it.readText() } }
                .getOrNull()?.let { publishCatalog(parseCatalog(it), loading = true) }
        }
        refreshInstalledRows()
    }

    suspend fun loadCatalog(context: Context) {
        initialize(context)
        val bundled = runCatching {
            context.assets.open(MODEL_CATALOG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (bundled != null) publishCatalog(parseCatalog(bundled), loading = true)
        mutableState.value = mutableState.value.copy(loadingCatalog = true)
        val latest = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(CATALOG_URL).build()
                TtsUtil.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Không tải được danh mục VBook (${response.code})")
                    response.body?.string() ?: error("Danh mục VBook rỗng")
                }
            }
        }.getOrNull()
        if (latest != null) {
            val local = bundled?.let(::parseCatalog).orEmpty()
            val verified = local.associateBy { it.id }
            val published = local + parseCatalog(latest).map { model ->
                val known = verified[model.id]
                if (known?.checksum == model.checksum && model.catalogSpeakerNames.isEmpty()) {
                    model.copy(catalogSpeakerNames = known.catalogSpeakerNames)
                } else model
            }
            publishCatalog(published.associateBy { it.id }.values.toList(), loading = false)
        }
        else mutableState.value = mutableState.value.copy(loadingCatalog = false)
    }

    suspend fun installModel(id: String) = withContext(Dispatchers.IO) {
        val context = appContext ?: error("TTS chưa khởi tạo")
        val row = mutableState.value.models.firstOrNull { it.model.id == id } ?: error("Không tìm thấy model $id")
        val model = row.model
        updateModelRow(id) { it.copy(progress = 0f) }
        val stage = File(context.cacheDir, "vbook-model-$id-${System.nanoTime()}")
        stage.mkdirs()
        val archive = File(stage, "model.zip.part")
        try {
            val request = Request.Builder().url(model.downloadUrl).build()
            TtsUtil.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Tải ${model.name} thất bại (${response.code})")
                val body = response.body ?: error("Gói ${model.name} rỗng")
                val expectedLength = body.contentLength()
                body.byteStream().use { input -> FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        check(downloaded <= model.sizeBytes) { "Gói ${model.name} vượt kích thước trong danh mục" }
                        output.write(buffer, 0, read)
                        val progress = if (expectedLength > 0L) downloaded.toFloat() / expectedLength else -1f
                        updateModelRow(id) { it.copy(progress = progress.coerceAtLeast(0f)) }
                    }
                } }
            }
            val actualHash = sha256(archive)
            if (!actualHash.equals(model.checksum, ignoreCase = true)) error("Checksum của ${model.name} không khớp")
            val outputRoot = File(stage, "model").apply { mkdirs() }
            val engine = engines.getValue(model.modelType)
            unzipSafely(archive, outputRoot, (model.sizeBytes * 2).coerceAtMost(512L * 1024 * 1024))
            check(engine.hasModelFiles(outputRoot)) { "Gói ${model.name} thiếu file model ${engine.format()}" }
            val configType = JSONObject(File(outputRoot, "config.json").readText()).optString("model_type")
            if (engine.format() == "mnn") {
                check(configType == engine.id()) { "Cấu hình ${model.name} không khớp engine ${engine.name()}" }
            }
            engineLock.lock()
            try {
                val target = modelDirectory(context, id)
                if (activeEngine?.modelId == id) {
                    releaseEngine(activeEngine)
                    activeEngine = null
                }
                target.parentFile?.mkdirs()
                val backup = File(stage, "previous-model")
                if (target.exists() && !target.renameTo(backup)) error("Không thể thay thế model ${model.name}")
                if (!outputRoot.renameTo(target)) {
                    if (backup.exists() && !backup.renameTo(target)) backup.copyRecursively(target, overwrite = true)
                    error("Không thể lưu model ${model.name}")
                }
            } finally {
                engineLock.unlock()
            }
            updateModelRow(id) { it.copy(progress = null) }
            refreshInstalledRows()
            mutableState.value = mutableState.value.copy(message = "Đã cài ${model.name}")
        } catch (error: Throwable) {
            updateModelRow(id) { it.copy(progress = null) }
            mutableState.value = mutableState.value.copy(message = error.message ?: "Không tải được ${model.name}")
            throw error
        } finally {
            archive.delete()
            stage.deleteRecursively()
        }
    }

    suspend fun removeModel(id: String) = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext
        engineLock.lock()
        try {
            if (activeEngine?.modelId == id) {
                releaseEngine(activeEngine)
                activeEngine = null
            }
            modelDirectory(context, id).deleteRecursively()
        } finally {
            engineLock.unlock()
        }
        refreshInstalledRows()
        mutableState.value = mutableState.value.copy(message = "Đã xóa model")
    }

    fun voices(): List<JsonObject> {
        val context = appContext ?: return emptyList()
        val rows = mutableState.value.models.filter { it.installed }
        return rows.flatMap { row ->
            val model = row.model
            val names = speakerNames(modelDirectory(context, model.id), model.speakerCount)
            names.mapIndexed { index, speaker ->
                buildJsonObject {
                    put("voice_id", "vbook:${model.id}:$index")
                    put("name", if (names.size == 1) model.name else "${model.name} · $speaker")
                    put("gender", model.gender.uppercase(Locale.ROOT))
                    put("tier", "${engineLabel(model.modelType)} · ${model.description}")
                    put("language", model.language)
                    put("provider", "VBook AI · ngoại tuyến")
                    put("icon", "")
                    put("needs_api_key", false)
                }
            }
        }
    }

    fun isModelInstalled(context: Context, id: String): Boolean =
        File(modelDirectory(context, id), "config.json").isFile

    fun isConfigured(): Boolean = mutableState.value.runtimeReady && mutableState.value.models.any { it.installed }

    suspend fun synthesize(text: String, voiceId: String, speed: Double): ByteArray = withContext(Dispatchers.IO) {
        val context = appContext ?: error("TTS chưa khởi tạo")
        if (!mutableState.value.runtimeReady) error("Bản cài này thiếu thư viện giọng đọc ARM64")
        val modelId = voiceId.substringBefore(':')
        val speaker = voiceId.substringAfter(':', "0").toIntOrNull() ?: 0
        val model = mutableState.value.models.firstOrNull { it.model.id == modelId && it.installed }?.model
            ?: error("Model VBook chưa được tải")
        engineLock.lock()
        try {
            if (engines.getValue(model.modelType).usesCommonData()) ensureCommonData(context)
            val engine = ensureEngine(context, model)
            val loader = engine.runtime.loader
            val continuationClass = loader.loadClass("kotlin.coroutines.Continuation")
            val method = engine.instance.javaClass.methods.firstOrNull { it.name == "d" && it.parameterTypes.size == 7 }
                ?: error("API synthesize của VBook không tìm thấy")
            val language = engine.language
            val generated = invokeSuspend(engine.runtime, continuationClass, method, engine.instance, arrayOf(text, speaker, speed, false, false, language, null))
            val pcm = generated as? ShortArray ?: error("VBook trả về định dạng âm thanh không hợp lệ")
            val sampleRate = engine.instance.javaClass.getMethod("e").invoke(engine.instance) as? Int ?: engine.modelRate
            pcmToWav(pcm, sampleRate)
        } finally {
            engineLock.unlock()
        }
    }

    private suspend fun ensureEngine(context: Context, model: VBookModel): LoadedEngine {
        val runtime = getRuntime(context)
        activeEngine?.takeIf { it.modelId == model.id && it.runtime === runtime }?.let { return it }
        activeEngine?.let(::releaseEngine)
        activeEngine = null
        val module = engines.getValue(model.modelType)
        val modelDir = modelDirectory(context, model.id)
        check(module.hasModelFiles(modelDir)) { "Tải lại ${model.name}: thiếu file model ${module.format()}" }
        val config = JSONObject(File(modelDir, "config.json").readText())
        val sampleRateHint = config.optJSONObject("audio")?.optInt("sample_rate", model.sampleRate)
            ?: config.optInt("sample_rate", model.sampleRate)
        val languageList = config.optJSONArray("languages")
        val languages = if (languageList != null) {
            (0 until languageList.length()).map { languageList.getString(it) }
        } else {
            listOf(model.language)
        }
        val language = if (model.language == "mul") languages.firstOrNull() ?: "en"
            else model.language.substringBefore('-').ifBlank { "vi" }
        val speakers = speakerNames(modelDir, model.speakerCount)
        val speakerMap = speakers.mapIndexed { index, name -> index to name }.toMap()
        val genderEnum = runtime.loader.loadClass("em")
        val gender = genderEnum.getMethod("valueOf", String::class.java).invoke(null, when (model.gender.lowercase(Locale.ROOT)) {
            "male" -> "MALE"
            "female" -> "FEMALE"
            else -> "MULTI"
        })
        val engineType = module.runtimeType(runtime.loader)
        val metadata = runtime.loader.loadClass("km")
            .getConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Map::class.java, genderEnum, Long::class.javaPrimitiveType,
                engineType.declaringJavaClass, String::class.java, List::class.java)
            .newInstance(model.id, model.name, model.language, sampleRateHint,
                model.speakerCount, speakerMap, gender, model.sizeBytes, engineType, model.format, languages)
        val pathFactory = runtime.loader.loadClass("dv0").getMethod("h", String::class.java, Boolean::class.javaPrimitiveType)
        val path = pathFactory.invoke(null, modelDir.absolutePath, false)
        val instance = module.createEngine(runtime.loader, engineType)
            ?: error("VBook không tạo được engine ${module.name()}")
        val engineClass = instance.javaClass
        val loadMethod = engineClass.methods.first { it.name == "c" && it.parameterTypes.size == 3 }
        val continuationClass = runtime.loader.loadClass("kotlin.coroutines.Continuation")
        try {
            val loaded = invokeSuspend(runtime, continuationClass, loadMethod, instance, arrayOf(metadata, path, null))
            if (loaded != true) {
                val nativeError = runCatching { engineClass.getMethod("lastError").invoke(instance) as? String }.getOrNull()
                error(nativeError ?: "VBook không nạp được model ${model.name}")
            }
            val sampleRate = runCatching { engineClass.getMethod("e").invoke(instance) as? Int }.getOrNull()
                ?: sampleRateHint
            return LoadedEngine(model.id, runtime, instance, sampleRate, language).also { activeEngine = it }
        } catch (error: Throwable) {
            runCatching { engineClass.getMethod("a").invoke(instance) }
            throw error
        }
    }

    private suspend fun invokeSuspend(
        runtime: RuntimeHandle,
        continuationClass: Class<*>,
        method: java.lang.reflect.Method,
        target: Any,
        args: Array<Any?>,
    ): Any? = suspendCancellableCoroutine { completion ->
        try {
            val emptyContextClass = runtime.loader.loadClass("g36")
            val coroutineContext = emptyContextClass.getField("a").get(null)
            val continuation = Proxy.newProxyInstance(runtime.loader, arrayOf(continuationClass)) { _, called, values ->
                when (called.name) {
                    "getContext" -> coroutineContext
                    "resumeWith" -> {
                        val result = values?.firstOrNull()
                        val failure = unwrapFailure(result)
                        if (failure != null) completion.resumeWithException(failure) else completion.resume(result)
                        null
                    }
                    "toString" -> "NovelBook VBook continuation"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            }
            val bridgeClass = generateContinuationClass(runtime.loader, continuationClass)
            val bridge = bridgeClass.getConstructor(continuationClass).newInstance(continuation)
            val callArgs = args.copyOf()
            callArgs[callArgs.lastIndex] = bridge
            val immediate = method.invoke(target, *callArgs)
            val suspended = runtime.loader.loadClass("oz3").getField("a").get(null)
            if (immediate !== suspended) completion.resume(immediate)
        } catch (error: Throwable) {
            val actual = if (error is InvocationTargetException) error.targetException else error
            if (actual is kotlinx.coroutines.CancellationException) throw actual
            completion.resumeWithException(actual)
        }
    }

    private fun generateContinuationClass(loader: ClassLoader, continuationClass: Class<*>): Class<*> {
        synchronized(bridgeClasses) {
            bridgeClasses[loader]?.let { return it }
            val id = bridgeIds.incrementAndGet()
            val className = "vn.tramtruyen.reader.engine.speech.generated.VBookContinuation$id"
            val descriptor = "L${className.replace('.', '/')};"
            val bridgeType = TypeId.get<Any>(descriptor)
            val superType = TypeId.get<Any>("Lev3;")
            val callbackType = TypeId.get<Any>("Lkotlin/coroutines/Continuation;")
            val objectType = TypeId.OBJECT
            val maker = DexMaker()
            maker.declare(bridgeType, "VBook runtime bridge", java.lang.reflect.Modifier.PUBLIC, superType)
            val constructor = bridgeType.getConstructor(callbackType)
            val constructorCode = maker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            val self = constructorCode.getThis(bridgeType)
            val callback: Local<Any> = constructorCode.getParameter(0, callbackType)
            constructorCode.invokeDirect(superType.getConstructor(callbackType), null, self, callback)
            constructorCode.returnVoid()
            val invoke = bridgeType.getMethod(objectType, "invokeSuspend", objectType)
            val invokeCode = maker.declare(invoke, java.lang.reflect.Modifier.PUBLIC)
            invokeCode.returnValue(invokeCode.getParameter(0, objectType))
            val output = File(appContext?.codeCacheDir ?: File("."), "vbook-bridge").apply { mkdirs() }
            val generatedLoader = maker.generateAndLoad(loader, output)
            return generatedLoader.loadClass(className).also { bridgeClasses[loader] = it }
        }
    }

    private fun getRuntime(context: Context): RuntimeHandle {
        runtimeHandle?.let { return it }
        val runtimeCache = File(context.codeCacheDir, RUNTIME_ASSET).apply { mkdirs() }
        val dexArchives = (1..4).map { index ->
            val name = "engine-$index.jar"
            val destination = File(runtimeCache, name)
            if (!destination.isFile) {
                val temporary = File.createTempFile("engine-", ".jar", runtimeCache)
                try {
                    FileOutputStream(temporary).use { output ->
                        check(temporary.setReadOnly()) { "Không thể khóa mã giọng đọc" }
                        context.assets.open("$RUNTIME_ASSET/$name").use { input -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(destination)) { "Không thể chuẩn bị mã giọng đọc" }
                } finally {
                    temporary.delete()
                }
            }
            destination
        }
        val info = context.applicationInfo
        val libraryPaths = listOfNotNull(info.sourceDir, *info.splitSourceDirs.orEmpty())
            .map { "$it!/lib/arm64-v8a" }
        val loader = VBookDexClassLoader(
            dexArchives.joinToString(File.pathSeparator) { it.absolutePath },
            runtimeCache.absolutePath,
            (listOf(info.nativeLibraryDir) + libraryPaths).joinToString(File.pathSeparator),
            context.classLoader,
        )
        val appClass = loader.loadClass("q21")
        val appContextField = appClass.getDeclaredField("m").apply { isAccessible = true }
        appContextField.set(null, context.applicationContext)
        return RuntimeHandle(loader).also { runtimeHandle = it }
    }

    private class VBookDexClassLoader(dexPath: String, optimizedDirectory: String, librarySearchPath: String, parent: ClassLoader) :
        DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if ('.' !in name || name == "kotlin.coroutines.Continuation") {
                synchronized(this) {
                    findLoadedClass(name)?.let { return it }
                    val embeddedClass = try {
                        findClass(name)
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                    embeddedClass?.let { loaded ->
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private fun ensureCommonData(context: Context) {
        val dataDirectory = File(context.filesDir, "tts/data")
        val marker = File(dataDirectory, ".vbook-data-version")
        if (marker.isFile && marker.readText().trim() == DATA_VERSION.toString()) return
        val archive = File(context.cacheDir, "vbook-tts-data.zip")
        val stage = File(context.cacheDir, "vbook-tts-data-${System.nanoTime()}").apply { mkdirs() }
        try {
            TtsUtil.http.newCall(Request.Builder().url(COMMON_DATA_URL).build()).execute().use { response ->
                if (!response.isSuccessful) error("Không tải được dữ liệu dùng chung VBook (${response.code})")
                response.body?.byteStream()?.use { input -> FileOutputStream(archive).use { input.copyTo(it) } }
                    ?: error("Dữ liệu dùng chung VBook rỗng")
            }
            unzipSafely(archive, stage, 100L * 1024 * 1024)
            if (!File(stage, "en-word_id.bin").isFile || !File(stage, "vi-word_id.bin").isFile || !File(stage, "sea_g2p.bin").isFile) {
                error("Gói dữ liệu dùng chung VBook không hợp lệ")
            }
            if (dataDirectory.exists()) dataDirectory.deleteRecursively()
            dataDirectory.mkdirs()
            stage.listFiles()?.forEach { source ->
                if (!source.renameTo(File(dataDirectory, source.name))) source.copyRecursively(File(dataDirectory, source.name), overwrite = true)
            }
            marker.writeText(DATA_VERSION.toString())
        } finally {
            archive.delete()
            stage.deleteRecursively()
        }
    }

    private fun releaseEngine(engine: LoadedEngine?) {
        engine ?: return
        runCatching { engine.instance.javaClass.getMethod("a").invoke(engine.instance) }
    }

    private fun refreshInstalledRows() {
        val context = appContext ?: return
        val root = File(context.filesDir, "tts/models")
        installedIds.clear()
        root.listFiles()?.filter { it.isDirectory && File(it, "config.json").isFile }?.forEach { installedIds.add(it.name) }
        mutableState.value = mutableState.value.copy(
            runtimeReady = "arm64-v8a" in Build.SUPPORTED_ABIS &&
                bundledLibraries.containsAll(requiredNativeLibraries),
            models = mutableState.value.models.map { row ->
                val installed = row.model.id in installedIds
                row.copy(
                    installed = installed,
                    voiceCount = if (installed) speakerNames(modelDirectory(context, row.model.id), row.model.speakerCount).size else row.model.speakerCount,
                    progress = null,
                )
            },
        )
    }

    private fun publishCatalog(models: List<VBookModel>, loading: Boolean) {
        val context = appContext
        val old = mutableState.value.models.associateBy { it.model.id }
        val ids = context?.let { ctx -> File(ctx.filesDir, "tts/models").listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty() }.orEmpty()
        mutableState.value = mutableState.value.copy(
            models = models.map { model ->
                val installed = model.id in ids
                VBookModelRow(model, installed, if (installed && context != null) speakerNames(modelDirectory(context, model.id), model.speakerCount).size else model.speakerCount, old[model.id]?.progress)
            },
            loadingCatalog = loading,
        )
    }

    private fun updateModelRow(id: String, transform: (VBookModelRow) -> VBookModelRow) {
        mutableState.value = mutableState.value.copy(models = mutableState.value.models.map { if (it.model.id == id) transform(it) else it })
    }

    private fun parseCatalog(text: String): List<VBookModel> {
        val array = JSONArray(text)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.optString("id")
                val modelType = item.optString("model_type", "piper").lowercase(Locale.ROOT)
                val downloadUrl = item.optString("download_url", "")
                val checksum = item.optString("checksum", "")
                if (!id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) continue
                if (modelType == "vieneu_nano" && id != TtsVoicePolicy.NANO_V4_MODEL) continue
                val engine = engines[modelType] ?: continue
                val format = item.optString("model_format", engine.format())
                if (!engine.accepts(format)) continue
                val size = item.optLong("size_bytes")
                if (size !in 1..(512L * 1024 * 1024)) continue
                if (!downloadUrl.startsWith("https://github.com/Darkrai9x/vbook-settings/releases/download/ai-tts/")) continue
                if (!checksum.matches(Regex("[a-fA-F0-9]{64}"))) continue
                add(VBookModel(
                    id = id, name = item.optString("name", id),
                    language = item.optString("language", ""), sizeBytes = size,
                    description = item.optString("description", ""), checksum = checksum,
                    downloadUrl = downloadUrl, speakerCount = item.optInt("num_speakers", 1).coerceAtLeast(1),
                    gender = item.optString("gender", "multi"), modelType = modelType,
                    format = format, sampleRate = item.optInt("sample_rate", engine.sampleRate()),
                    previewUrl = item.optString("preview_url").takeIf {
                        it.startsWith("https://raw.githubusercontent.com/Darkrai9x/vbook-settings/main/tts/sample/")
                    },
                    catalogSpeakerNames = item.optJSONArray("speaker_names")?.let { names ->
                        (0 until names.length()).map { names.optString(it) }.filter(String::isNotBlank)
                    }.orEmpty(),
                ))
            }
        }
    }

    private fun speakerNames(directory: File, fallbackCount: Int): List<String> {
        val config = runCatching { JSONObject(File(directory, "config.json").readText()) }.getOrNull()
        val list = config?.optJSONArray("speakers")
        if (list != null && list.length() > 0) return (0 until list.length()).map { list.optString(it).ifBlank { "Giọng ${it + 1}" } }
        val objectMap = config?.optJSONObject("speaker_id_map")
        if (objectMap != null && objectMap.length() > 0) {
            val byIndex = objectMap.keys().asSequence().mapNotNull { key ->
                val value = objectMap.opt(key)
                val index = if (value is Number) value.toInt() else key.toIntOrNull()
                val name = if (value is String) value else key.takeIf { index != null }
                if (index == null || name == null) null else index to name
            }.sortedBy { it.first }.map { it.second }.toList()
            if (byIndex.isNotEmpty()) return byIndex
        }
        return List(fallbackCount.coerceAtLeast(1)) { index -> "Giọng ${index + 1}" }
    }

    private fun unzipSafely(archive: File, destination: File, maxExpandedBytes: Long) {
        val canonicalRoot = destination.canonicalFile
        var total = 0L
        ZipInputStream(FileInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) error("Đường dẫn không hợp lệ trong gói tải")
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxExpandedBytes) error("Gói model giải nén vượt giới hạn")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun modelDirectory(context: Context, id: String) = File(File(context.filesDir, "tts/models"), id)

    internal fun engineLabel(engine: String): String = engines[engine]?.name() ?: engine

    private fun pcmToWav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLength = pcm.size * 2
        val output = ByteArrayOutputStream(dataLength + 44)
        fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun writeInt(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff); output.write((value ushr 16) and 0xff); output.write((value ushr 24) and 0xff) }
        fun writeShort(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
        writeAscii("RIFF"); writeInt(36 + dataLength); writeAscii("WAVE"); writeAscii("fmt "); writeInt(16)
        writeShort(1); writeShort(1); writeInt(sampleRate); writeInt(sampleRate * 2); writeShort(2); writeShort(16)
        writeAscii("data"); writeInt(dataLength)
        pcm.forEach { value -> writeShort(value.toInt()) }
        return output.toByteArray()
    }

    private fun unwrapFailure(result: Any?): Throwable? {
        if (result == null || result.javaClass.name != "wre") return null
        val field = runCatching { result.javaClass.getDeclaredField("a").apply { isAccessible = true } }.getOrNull() ?: return null
        return field.get(result) as? Throwable
    }
}

class VBookTtsProvider : TtsProvider {
    override val voiceList: List<JsonObject> get() = VBookTtsRuntime.voices()
    override fun isConfigured(): Boolean = VBookTtsRuntime.isConfigured()
    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray =
        VBookTtsRuntime.synthesize(text, voiceId, speed)
}
