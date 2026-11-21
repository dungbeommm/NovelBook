package vn.tramtruyen.reader.engine.speech

import android.content.Context
import vn.tramtruyen.reader.engine.catalog.DownloadOptions
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Runtime, catalog, downloader and safe importer for Sherpa/Piper voices. */
object OfflineTtsModelManager {
    data class Model(
        val id: String, val name: String, val gender: String, val sizeBytes: Long,
        val downloadUrl: String, val checksum: String, val sampleRate: Int,
        val source: String = "network",
        val configUrl: String = "",
        val configChecksum: String = "",
        val demoUrl: String = "",
        val espeakVoiceOverride: String = "",
        val fromVBook: Boolean = false,
    )

    private lateinit var app: Context
    private lateinit var root: File
    private val catalog = ArrayList<Model>()
    private val imported = ArrayList<Model>()
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = context.applicationContext
            root = File(app.filesDir, "offline_tts").apply { mkdirs() }
            loadCatalogLocked()
            loadImportedIndex()
            initialized = true
        }
    }

    /**
     * Nap danh muc giong doc.
     * Nap danh muc giong doc tu assets (reader_catalog/tts/models.json).
     */
    private fun loadCatalogLocked() {
        val text = readCatalogText()
        val a = parseCatalogArray(text) ?: return
        catalog.clear()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id"); if (id.isBlank()) continue
            val url = o.optString("download_url")
                .ifBlank { o.optString("onnx_url").ifBlank { o.optString("onnxUrl") } }
            if (url.isBlank()) continue
            catalog += Model(
                id = id, name = o.optString("name").ifBlank { o.optString("displayName", id) },
                gender = o.optString("gender", "neutral"),
                sizeBytes = o.optLong("size_bytes", o.optLong("sizeBytes", 0L)),
                downloadUrl = url,
                checksum = o.optString("checksum", "").lowercase(),
                sampleRate = o.optInt("sample_rate", 22050),
                source = "network",
                configUrl = o.optString("config_url")
                    .ifBlank { o.optString("json_url").ifBlank { o.optString("jsonUrl") } },
                configChecksum = o.optString("config_checksum", "").lowercase(),
                demoUrl = o.optString("demo_url").ifBlank { o.optString("demoUrl") },
                espeakVoiceOverride = o.optString("espeak_voice_override"),
                fromVBook = o.optBoolean("from_vbook"),
            )
        }
    }

    private fun readCatalogText(): String {
        return runCatching {
            app.assets.open("reader_catalog/tts/models.json").bufferedReader().use { it.readText() }
        }.getOrDefault("[]")
    }

    /** Chap nhan ca dang mang [..] lan dang {"models":[..]} cua kho GitHub. */
    private fun parseCatalogArray(text: String): JSONArray? = runCatching {
        val t = text.trim()
        if (t.startsWith("[")) JSONArray(t)
        else JSONObject(t).let { o -> o.optJSONArray("models") ?: o.optJSONArray("voices") }
    }.getOrNull()

    /** Nap lai danh muc sau khi models.json duoc lam moi tu GitHub. */
    fun reloadCatalog() {
        if (!initialized) return
        synchronized(this) { loadCatalogLocked() }
    }

    fun models(): List<Model> = synchronized(this) {
        (imported + catalog).distinctBy { it.id }
    }
    fun model(id: String): Model? = models().firstOrNull { it.id == id }
    fun modelDir(id: String): File = File(File(root, "models"), safeId(id))
    fun isInstalled(id: String): Boolean {
        val dir = modelDir(id)
        return File(dir, "model.onnx").length() > 1_000_000L && File(dir, "tokens.txt").length() > 32L
    }

    fun sharedDataDir(): File? {
        val dir = File(root, "shared")
        val espeak = File(dir, "espeak-ng-data")
        if (File(dir, ".ready").exists() && espeak.isDirectory) return dir
        return null
    }

    fun runtimeReady(): Boolean = sharedDataDir() != null

    /** One-tap download of the shared eSpeak-NG frontend required by every voice. */
    suspend fun prepareSharedData(onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        sharedDataDir()?.let { onProgress(100); return@withContext it }
        check(DownloadOptions.voiceModelsAllowed()) { "Đã tắt tải dữ liệu giọng đọc trong Cài đặt" }
        val target = File(root, "shared")
        val temp = File(root, "shared.tmp").apply { deleteRecursively(); mkdirs() }
        val dataUrl = "https://huggingface.co/buckets/uri410/NovelBook_Cloud/resolve/tts/piper/shared/espeak/tts-data.zip"
        val dataChecksum = "000f96193a48015b865cfb67616ad256b613a466aead6f2d3f182a14ff4a5015"
        val archive = File(root, "tts-data.zip.part")
        try {
            val req = Request.Builder().url(dataUrl).header("User-Agent", "TramTruyen/3.8").build()
            TtsUtil.http.newCall(req).execute().use { res ->
                check(res.isSuccessful) { "Không tải được dữ liệu phát âm: HTTP ${res.code}" }
                val body = res.body ?: error("Dữ liệu phát âm rỗng")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    FileOutputStream(archive, false).use { out ->
                        val buf = ByteArray(128 * 1024); var done = 0L; var last = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            check(done <= 100L * 1024 * 1024) { "Tài nguyên trình chạy quá lớn" }
                            val p = if (total != null) ((done * 85 / total).toInt()).coerceIn(0, 85) else 0
                            if (p != last) { last = p; onProgress(p) }
                        }
                        out.fd.sync()
                    }
                }
            }
            check(sha256(archive) == dataChecksum) {
                "Checksum dữ liệu phát âm không khớp; tệp tải về có thể đã bị thay đổi"
            }
            onProgress(88)
            ZipInputStream(FileInputStream(archive).buffered()).use { unzipSafe(it, temp, 150L * 1024 * 1024) }
            check(File(temp, "espeak-ng-data").isDirectory) { "Thiếu espeak-ng-data" }
            File(temp, ".ready").writeText("1")
            target.deleteRecursively()
            check(temp.renameTo(target)) { "Không thể cài dữ liệu phát âm" }
            onProgress(100)
            target
        } finally {
            archive.delete()
            if (temp.exists() && temp != target) temp.deleteRecursively()
        }
    }

    suspend fun install(id: String, onProgress: (Int) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        cancellableResult {
            val m = model(id) ?: error("Không có model $id")
            check(m.source == "network") { "Model này đã được nhập từ máy" }
            if (isInstalled(id)) {
                // isInstalled() chỉ nhìn kích thước tệp — chưa đủ để tin model
                // nguyên vẹn. Với model dạng ONNX trực tiếp (có configUrl),
                // checksum trong catalog chính là SHA-256 của model.onnx: đối
                // chiếu trước khi báo xong; lệch thì xoá và tải lại từ đầu.
                // (Dạng ZIP: checksum là của cả gói zip nên không áp được cho
                // model.onnx đã giải nén — giữ nguyên đường cũ.)
                val checksumIsOnnx = m.configUrl.isNotBlank() && m.checksum.isNotBlank()
                val intact = !checksumIsOnnx ||
                    sha256(File(modelDir(id), "model.onnx")) == m.checksum.lowercase()
                if (intact) {
                    // Model cũ có thể thiếu tokens/metadata — chuẩn hoá lại trước khi báo xong.
                    finalizeModelDir(modelDir(id), preferredName = m.name)
                    onProgress(100); return@cancellableResult
                }
                modelDir(id).deleteRecursively()
            }
            check(DownloadOptions.voiceModelsAllowed()) { "Đã tắt tải model giọng đọc trong Cài đặt" }
            check(runtimeReady()) { "Hãy cài trình chạy TTS trước" }
            val modelsRoot = File(root, "models").apply { mkdirs() }
            val temp = File(modelsRoot, "$id.tmp").apply { deleteRecursively(); mkdirs() }
            try {
                if (m.configUrl.isNotBlank()) {
                    // Direct Piper pair: CDN provides one ONNX plus its JSON sidecar.
                    // finalizeModelDir() converts phoneme_id_map to Sherpa tokens and
                    // adds the metadata expected by the offline runtime.
                    val onnx = File(temp, "model.onnx")
                    downloadToFile(m.downloadUrl, onnx, 300L * 1024 * 1024) { done, total ->
                        val expected = total.takeIf { it > 0 } ?: m.sizeBytes
                        onProgress(if (expected > 0) (done * 85 / expected).toInt().coerceIn(0, 85) else 0)
                    }
                    check(m.checksum.isNotBlank() && m.configChecksum.isNotBlank()) {
                        "Model $id thiếu checksum bắt buộc"
                    }
                    check(sha256(onnx) == m.checksum) {
                        "Checksum model không khớp (file hỏng hoặc URL sai)"
                    }
                    val configFile = File(temp, "model.onnx.json")
                    downloadToFile(m.configUrl, configFile, 8L * 1024 * 1024)
                    check(sha256(configFile) == m.configChecksum) {
                        "Checksum cấu hình model không khớp"
                    }
                    onProgress(90)
                    if (m.espeakVoiceOverride.isNotBlank()) {
                        val config = JSONObject(configFile.readText())
                        val espeak = config.optJSONObject("espeak") ?: JSONObject()
                        espeak.put("voice", m.espeakVoiceOverride)
                        config.put("espeak", espeak)
                        configFile.writeText(config.toString())
                    }
                } else {
                    val downloads = File(root, "downloads").apply { mkdirs() }
                    val zip = File(downloads, "$id.zip.part")
                    try {
                        downloadToFile(m.downloadUrl, zip, 300L * 1024 * 1024) { done, total ->
                            val expected = total.takeIf { it > 0 } ?: m.sizeBytes
                            val p = if (expected > 0) (done * 88 / expected).toInt().coerceIn(0, 88) else 0
                            onProgress(p)
                        }
                        check(m.checksum.isNotBlank()) { "Model $id thiếu checksum bắt buộc" }
                        val got = sha256(zip)
                        check(got == m.checksum.lowercase()) {
                            "Checksum model không khớp (file hỏng hoặc URL sai)"
                        }
                        ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
                            unzipSafe(zin, temp, 300L * 1024 * 1024)
                        }
                    } finally {
                        zip.delete()
                    }
                }
                onProgress(92)
                // If the archive is nested, flatten its ONNX and JSON files first.
                flattenOnnxJsonInto(temp)
                finalizeModelDir(temp, preferredName = m.name)
                val target = modelDir(id)
                target.deleteRecursively()
                check(temp.renameTo(target)) { "Không thể hoàn tất model" }
                finalizeModelDir(target, preferredName = m.name)
                check(isInstalled(id)) { "Model sau cài đặt không hợp lệ" }
                onProgress(100)
            } finally {
                if (temp.exists()) temp.deleteRecursively()
            }
        }
    }

    private fun downloadToFile(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val request = Request.Builder().url(url).header("User-Agent", "TramTruyen/3.10").build()
        TtsUtil.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                if (response.code == 404) "Không tìm thấy file giọng đọc (HTTP 404)"
                else "Tải giọng đọc thất bại (HTTP ${response.code})"
            }
            val body = response.body ?: error("Phản hồi tải giọng đọc rỗng")
            val total = body.contentLength().coerceAtLeast(0L)
            target.parentFile?.mkdirs()
            var downloaded = 0L
            var lastProgressBucket = -1L
            body.byteStream().use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        downloaded += n
                        check(downloaded <= maxBytes) { "File giọng đọc vượt giới hạn an toàn" }
                        output.write(buffer, 0, n)
                        val bucket = if (total > 0) downloaded * 100 / total else downloaded / (512 * 1024)
                        if (bucket != lastProgressBucket) {
                            lastProgressBucket = bucket
                            onProgress(downloaded, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            check(downloaded > 0L) { "File giọng đọc rỗng" }
        }
    }

    /** Import a Piper ZIP containing exactly one ONNX and its matching JSON config. */
    suspend fun importFromUri(context: Context, uri: Uri): Result<Model> = withContext(Dispatchers.IO) {
        cancellableResult {
            check(runtimeReady()) { "Hãy cài trình chạy TTS trước" }
            val fallback = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip").orEmpty().ifBlank { "Giọng nhập từ máy" }
            val staging = File(root, "import-${System.nanoTime()}").apply { mkdirs() }
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { unzipPiperFlat(it, staging) }
                } ?: error("Không mở được file ZIP")
                val onnxFiles = staging.listFiles()?.filter { it.extension.equals("onnx", true) }.orEmpty()
                check(onnxFiles.size == 1) { "ZIP phải chứa đúng một file .onnx" }
                val onnx = onnxFiles.single()
                val config = staging.listFiles()?.firstOrNull {
                    it.name.equals(onnx.name + ".json", true) || it.name.equals(onnx.nameWithoutExtension + ".json", true)
                } ?: error("ZIP thiếu file JSON khớp với ${onnx.name}")
                val cfg = JSONObject(config.readText())
                val phonemeMap = cfg.optJSONObject("phoneme_id_map") ?: error("JSON không có phoneme_id_map của Piper")
                val phonemeType = cfg.optString("phoneme_type", "espeak")
                check(phonemeType.equals("espeak", true)) { "Hiện chỉ hỗ trợ model Piper dùng eSpeak" }
                val base = safeId(onnx.nameWithoutExtension.ifBlank { fallback })
                val id = uniqueImportedId(base)
                val target = modelDir(id).apply { deleteRecursively(); mkdirs() }
                val targetOnnx = File(target, "model.onnx")
                onnx.copyTo(targetOnnx, overwrite = true)
                config.copyTo(File(target, "model.onnx.json"), overwrite = true)
                // Ordinary Piper exports keep sample rate in the sidecar JSON,
                // while sherpa-onnx reads it from ONNX custom metadata.
                patchPiperMetadata(targetOnnx, cfg)
                writeTokens(phonemeMap, File(target, "tokens.txt"))
                File(target, ".sherpa-piper-v4").writeText("1")
                check(isInstalled(id)) { "Model hoặc bảng token không hợp lệ" }
                val sampleRate = cfg.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050
                val model = Model(id, fallback, "neutral", onnx.length() + config.length(), "", "", sampleRate, "device")
                synchronized(this@OfflineTtsModelManager) {
                    imported.removeAll { it.id == id }
                    imported.add(model)
                    saveImportedIndex()
                }
                model
            } catch (e: Throwable) {
                throw e
            } finally { staging.deleteRecursively() }
        }
    }

    fun delete(id: String): Boolean {
        val deleted = modelDir(id).deleteRecursively()
        synchronized(this) {
            if (imported.removeAll { it.id == id }) saveImportedIndex()
        }
        return deleted
    }

    /**
     * Normalize ModelProto field 14 with a streaming rewrite. Piper packs can
     * already have sample_rate/voice keys; appending duplicates is invalid ONNX.
     * Existing non-metadata fields and unrelated metadata are preserved.
     */
    private fun patchPiperMetadata(model: File, cfg: JSONObject) {
        val sampleRate = cfg.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050
        val speakers = cfg.optInt("num_speakers", 1).coerceAtLeast(1)
        // `voice` is consumed directly by espeak_SetVoiceByName(). It MUST be
        // the machine voice code from the Piper JSON (for Ngọc Huyền: `vi`),
        // never the user-facing model/ZIP name. An invalid value aborts in JNI
        // before Kotlin can catch it.
        val espeakVoice = cfg.optJSONObject("espeak")
            ?.optString("voice", "")
            ?.trim()
            .orEmpty()
        check(Regex("^[A-Za-z][A-Za-z0-9_-]{1,31}$").matches(espeakVoice)) {
            "Mã giọng eSpeak trong JSON không hợp lệ: $espeakVoice"
        }
        val language = cfg.optJSONObject("language")
            ?.optString("name_english", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: espeakVoice
        val entries = linkedMapOf(
            "model_type" to "vits",
            "comment" to "piper",
            "language" to language,
            "voice" to espeakVoice,
            "has_espeak" to "1",
            "n_speakers" to speakers.toString(),
            "sample_rate" to sampleRate.toString(),
        )
        OnnxMetadata.rewrite(model, entries)
    }

    /**
     * Regenerate imported models using Sherpa's official Piper conversion.
     * v4 replaces duplicate keys and repairs r3-r6 models whose display name was incorrectly written into
     * ONNX `voice`, which makes native eSpeak abort before Kotlin can catch it.
     */
    fun prepareForInference(id: String) {
        val m = model(id) ?: error("Không tìm thấy model $id")
        val dir = modelDir(id)
        // Chuẩn hoá mọi model (mạng + import) trước khi native load.
        finalizeModelDir(dir, preferredName = m.name)
        check(isInstalled(id)) { "Model offline chưa sẵn sàng: thiếu model.onnx hoặc tokens.txt" }
    }

    /**
     * Chuẩn hoá thư mục model về dạng sherpa-onnx:
     *   model.onnx + tokens.txt (+ model.onnx.json nếu có Piper config)
     * Chấp nhận ZIP Piper phẳng (ten.onnx + ten.onnx.json / ten.json).
     */
    private fun finalizeModelDir(dir: File, preferredName: String = "") {
        if (!dir.isDirectory) return
        flattenOnnxJsonInto(dir)

        var onnx = File(dir, "model.onnx")
        if (onnx.length() <= 1_000_000L) {
            val found = dir.walkTopDown().firstOrNull {
                it.isFile && it.extension.equals("onnx", true) && it.length() > 1_000_000L
            }
            if (found != null && found != onnx) {
                found.copyTo(onnx, overwrite = true)
                if (found.absolutePath != onnx.absolutePath) found.delete()
            }
        }
        check(onnx.isFile && onnx.length() > 1_000_000L) { "Thiếu model.onnx hợp lệ" }

        // Tìm JSON Piper đi kèm.
        val config = sequenceOf(
            File(dir, "model.onnx.json"),
            File(dir, "model.json"),
        ).firstOrNull { it.isFile }
            ?: dir.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("json", true) &&
                    !it.name.startsWith(".") && it.length() in 64..(8L * 1024 * 1024)
            }

        val tokens = File(dir, "tokens.txt")
        val marker = File(dir, ".sherpa-piper-v4")

        if (config != null) {
            val cfg = runCatching { JSONObject(config.readText()) }.getOrNull()
            val map = cfg?.optJSONObject("phoneme_id_map")
            if (cfg != null && map != null) {
                // Luôn ghi tokens từ phoneme map (đúng format sherpa).
                if (!marker.isFile || tokens.length() <= 32L) {
                    check(cfg.optString("phoneme_type", "espeak").equals("espeak", true)) {
                        "Chỉ hỗ trợ model Piper dùng eSpeak"
                    }
                    // Chỉ patch metadata ONNX một lần.
                    if (!marker.isFile) patchPiperMetadata(onnx, cfg)
                    writeTokens(map, tokens)
                    marker.writeText(preferredName.ifBlank { "1" })
                }
                // Chuẩn hoá tên file JSON.
                val stdJson = File(dir, "model.onnx.json")
                if (config.absolutePath != stdJson.absolutePath) {
                    config.copyTo(stdJson, overwrite = true)
                }
            }
        }

        check(tokens.isFile && tokens.length() > 32L) {
            "ZIP model thiếu tokens.txt và không có JSON Piper để tạo bảng âm"
        }
    }

    /** Gom file .onnx/.json từ thư mục con lên root. */
    private fun flattenOnnxJsonInto(dir: File) {
        if (!dir.isDirectory) return
        val extras = dir.walkTopDown()
            .filter { it.isFile && it.parentFile != dir }
            .filter { it.extension.equals("onnx", true) || it.extension.equals("json", true) || it.name == "tokens.txt" }
            .toList()
        for (f in extras) {
            val dest = File(dir, f.name)
            if (!dest.exists()) f.copyTo(dest, overwrite = false)
        }
        // Nếu có đúng 1 onnx không tên model.onnx → đổi tên sau (finalize xử lý).
    }

    /** Exact tokens.txt format from Sherpa's official Piper conversion guide. */
    private fun writeTokens(map: JSONObject, out: File) {
        var count = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            val keys = map.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                if (token == "\n" || token == "\r" || token == "\t") continue
                val ids = map.optJSONArray(token) ?: continue
                if (ids.length() == 0) continue
                val id = ids.optInt(0, -1)
                if (id < 0) continue
                // Keep a literal space token as "  3". Replacing it with
                // <space> changes the symbol table and breaks Piper phonemes.
                w.append(token).append(' ').append(id.toString()).append('\n')
                count++
            }
        }
        check(count > 8) { "phoneme_id_map quá nhỏ" }
    }

    private fun unzipPiperFlat(zin: ZipInputStream, target: File) {
        var total = 0L
        while (true) {
            val e = zin.nextEntry ?: break
            if (!e.isDirectory) {
                val name = e.name.replace('\\', '/').substringAfterLast('/')
                check(!name.endsWith(".vmodel", true)) {
                    "VModel VMD1 cần runtime/SDK riêng; bản này chỉ hỗ trợ Piper ONNX + JSON"
                }
                if (name.endsWith(".onnx", true) || name.endsWith(".json", true)) {
                    val out = File(target, name)
                    check(!out.exists()) { "ZIP có tên file trùng nhau: $name" }
                    FileOutputStream(out).use { file ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            total += n
                            check(total <= 300L * 1024 * 1024) { "Model vượt giới hạn 300 MB" }
                            file.write(buf, 0, n)
                        }
                    }
                }
            }
            zin.closeEntry()
        }
    }

    internal fun unzipSafe(zin: ZipInputStream, target: File, maxBytes: Long) {
        val base = target.canonicalFile
        var total = 0L
        while (true) {
            val e = zin.nextEntry ?: break
            val out = File(base, e.name).canonicalFile
            check(out.path == base.path || out.path.startsWith(base.path + File.separator)) { "ZIP path không an toàn" }
            if (e.isDirectory) out.mkdirs() else {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { file ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = zin.read(buf); if (n < 0) break
                        total += n; check(total <= maxBytes) { "Dữ liệu giải nén quá lớn" }
                        file.write(buf, 0, n)
                    }
                }
            }
            zin.closeEntry()
        }
    }

    private fun loadImportedIndex() {
        imported.clear()
        val f = File(root, "imported-models.json")
        if (!f.isFile) return
        runCatching {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                imported += Model(o.getString("id"), o.optString("name", o.getString("id")),
                    o.optString("gender", "neutral"), o.optLong("size_bytes", 0), "", "",
                    o.optInt("sample_rate", 22050), "device")
            }
        }
    }

    private fun saveImportedIndex() {
        val a = JSONArray()
        imported.forEach { m -> a.put(JSONObject().put("id", m.id).put("name", m.name)
            .put("gender", m.gender).put("size_bytes", m.sizeBytes).put("sample_rate", m.sampleRate)) }
        val f = File(root, "imported-models.json")
        val tmp = File(root, "imported-models.json.tmp")
        tmp.writeText(a.toString(2))
        if (f.exists()) f.delete()
        check(tmp.renameTo(f)) { "Không lưu được danh sách model nhập" }
    }

    private fun uniqueImportedId(base: String): String {
        var id = "import-$base"; var n = 2
        while (model(id) != null || modelDir(id).exists()) { id = "import-$base-$n"; n++ }
        return id
    }

    private fun safeId(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-').take(80).ifBlank { "voice" }

    private inline fun <T> cancellableResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val b = ByteArray(128 * 1024)
            while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
