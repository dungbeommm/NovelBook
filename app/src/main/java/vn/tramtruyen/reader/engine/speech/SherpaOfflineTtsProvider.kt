package vn.tramtruyen.reader.engine.speech

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fully local VITS/Piper synthesis through sherpa-onnx. */
class SherpaOfflineTtsProvider : TtsProvider {
    private val mutex = Mutex()
    private var activeId: String? = null
    private var engine: OfflineTts? = null
    @Volatile var lastError: String? = null
        private set

    override val voiceList: List<JsonObject>
        get() = OfflineTtsModelManager.models().filter { OfflineTtsModelManager.isInstalled(it.id) }.map { m ->
            TtsUtil.voice(
                "offline:${m.id}", m.name,
                when (m.gender.lowercase()) { "male" -> "MALE"; "female" -> "FEMALE"; else -> "NEUTRAL" },
                "Đã tải · ${(m.sizeBytes / 1024 / 1024)} MB", "vi-VN",
                "AI ngoại tuyến", "offline", false,
            )
        }

    override fun isConfigured(): Boolean =
        OfflineTtsModelManager.runtimeReady() &&
            OfflineTtsModelManager.models().any { OfflineTtsModelManager.isInstalled(it.id) }

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? =
        mutex.withLock {
            lastError = null
            val id = voiceId.removePrefix("offline:")
            if (!OfflineTtsModelManager.runtimeReady()) {
                lastError = "Chưa cài trình chạy TTS (eSpeak). Vào Cài đặt → AI ngoại tuyến."
                return null
            }
            val model = OfflineTtsModelManager.model(id)
            if (model == null) {
                lastError = "Không có model offline: $id"
                return null
            }
            if (!OfflineTtsModelManager.isInstalled(model.id)) {
                lastError = "Model ${model.name} chưa được cài. Vào Cài đặt để tải."
                return null
            }
            withContext(Dispatchers.Default) {
                try {
                    val tts = load(model.id)
                    val clean = text.trim()
                    if (clean.isEmpty()) {
                        lastError = "Đoạn văn bản rỗng"
                        return@withContext null
                    }
                    currentCoroutineContext().ensureActive()
                    val audio = tts.generate(clean, sid = 0, speed = TtsUtil.clampSpeed(speed).toFloat())
                    currentCoroutineContext().ensureActive()
                    if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                        lastError = "Engine offline trả về audio rỗng"
                        null
                    } else {
                        floatPcmToWav(audio.samples, audio.sampleRate)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    lastError = e.message?.takeIf { it.isNotBlank() }
                        ?: "Lỗi engine offline: ${e.javaClass.simpleName}"
                    Log.e("SherpaOfflineTts", "synthesize failed", e)
                    null
                }
            }
        }

    private suspend fun load(id: String): OfflineTts {
        engine?.takeIf { activeId == id }?.let { return it }
        engine?.release(); engine = null; activeId = null
        OfflineTtsModelManager.prepareForInference(id)
        val shared = OfflineTtsModelManager.sharedDataDir()
            ?: OfflineTtsModelManager.prepareSharedData()
        val dir = OfflineTtsModelManager.modelDir(id)
        val onnx = java.io.File(dir, "model.onnx")
        val tokens = java.io.File(dir, "tokens.txt")
        check(onnx.isFile && onnx.length() > 1_000_000L) { "Thiếu model.onnx của $id" }
        check(tokens.isFile && tokens.length() > 32L) { "Thiếu tokens.txt của $id" }
        val dataDir = java.io.File(shared, "espeak-ng-data")
        check(dataDir.isDirectory) { "Thiếu espeak-ng-data — hãy cài trình chạy TTS" }
        val cpu = Runtime.getRuntime().availableProcessors()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = if (cpu >= 6) 2 else 1,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.18f,
        )
        return OfflineTts(config = config).also { engine = it; activeId = id }
    }

    /** Shares the synthesis mutex so JNI never uses a released engine/model. */
    suspend fun deleteModel(id: String): Boolean = mutex.withLock {
        if (activeId == id) {
            engine?.release()
            engine = null
            activeId = null
        }
        withContext(Dispatchers.IO) { OfflineTtsModelManager.delete(id) }
    }

    private fun floatPcmToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        ascii("RIFF"); i32(36 + dataSize); ascii("WAVE"); ascii("fmt "); i32(16)
        i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
        ascii("data"); i32(dataSize)
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) pcm.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        out.write(pcm.array()); return out.toByteArray()
    }
}
