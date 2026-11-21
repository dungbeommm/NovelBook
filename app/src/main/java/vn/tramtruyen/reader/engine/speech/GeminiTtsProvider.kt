package vn.tramtruyen.reader.engine.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Google Gemini TTS (gemini-2.5-flash-preview-tts). Tra ve PCM L16 24kHz -> WAV.
 * (Khong co ffmpeg tren thiet bi nen giu dinh dang WAV thay vi MP3.)
 */
class GeminiTtsProvider(private val apiKey: String) : TtsProvider {

    // Google hay đổi tên model preview — thử lần lượt.
    private val models = listOf(
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-flash-tts",
        "gemini-2.0-flash-preview-tts",
    )
    private val jsonMedia = "application/json".toMediaType()
    private val maxRetries = 3
    @Volatile var lastError: String? = null
        private set

    /**
     * 30 giọng dựng sẵn theo danh mục chính thức của Gemini TTS.
     *
     * Google mô tả các giọng theo sắc thái thay vì giới tính, vì vậy để
     * NEUTRAL nhằm tránh lọc/hiển thị sai trong giao diện chọn giọng.
     */
    private val voices = linkedMapOf(
        "gemini:Zephyr" to Triple("Gemini Zephyr — Sáng rõ", "NEUTRAL", "multilingual"),
        "gemini:Puck" to Triple("Gemini Puck — Tươi vui", "NEUTRAL", "multilingual"),
        "gemini:Charon" to Triple("Gemini Charon — Thuyết minh", "NEUTRAL", "multilingual"),
        "gemini:Kore" to Triple("Gemini Kore — Chắc chắn", "NEUTRAL", "multilingual"),
        "gemini:Fenrir" to Triple("Gemini Fenrir — Hào hứng", "NEUTRAL", "multilingual"),
        "gemini:Leda" to Triple("Gemini Leda — Trẻ trung", "NEUTRAL", "multilingual"),
        "gemini:Orus" to Triple("Gemini Orus — Vững vàng", "NEUTRAL", "multilingual"),
        "gemini:Aoede" to Triple("Gemini Aoede — Thoáng nhẹ", "NEUTRAL", "multilingual"),
        "gemini:Callirrhoe" to Triple("Gemini Callirrhoe — Thư thái", "NEUTRAL", "multilingual"),
        "gemini:Autonoe" to Triple("Gemini Autonoe — Tươi sáng", "NEUTRAL", "multilingual"),
        "gemini:Enceladus" to Triple("Gemini Enceladus — Thì thầm", "NEUTRAL", "multilingual"),
        "gemini:Iapetus" to Triple("Gemini Iapetus — Rõ ràng", "NEUTRAL", "multilingual"),
        "gemini:Umbriel" to Triple("Gemini Umbriel — Dễ nghe", "NEUTRAL", "multilingual"),
        "gemini:Algieba" to Triple("Gemini Algieba — Mượt mà", "NEUTRAL", "multilingual"),
        "gemini:Despina" to Triple("Gemini Despina — Êm mượt", "NEUTRAL", "multilingual"),
        "gemini:Erinome" to Triple("Gemini Erinome — Trong rõ", "NEUTRAL", "multilingual"),
        "gemini:Algenib" to Triple("Gemini Algenib — Khàn nhẹ", "NEUTRAL", "multilingual"),
        "gemini:Rasalgethi" to Triple("Gemini Rasalgethi — Dẫn chuyện", "NEUTRAL", "multilingual"),
        "gemini:Laomedeia" to Triple("Gemini Laomedeia — Năng động", "NEUTRAL", "multilingual"),
        "gemini:Achernar" to Triple("Gemini Achernar — Dịu nhẹ", "NEUTRAL", "multilingual"),
        "gemini:Alnilam" to Triple("Gemini Alnilam — Cứng cáp", "NEUTRAL", "multilingual"),
        "gemini:Schedar" to Triple("Gemini Schedar — Cân bằng", "NEUTRAL", "multilingual"),
        "gemini:Gacrux" to Triple("Gemini Gacrux — Trưởng thành", "NEUTRAL", "multilingual"),
        "gemini:Pulcherrima" to Triple("Gemini Pulcherrima — Trực diện", "NEUTRAL", "multilingual"),
        "gemini:Achird" to Triple("Gemini Achird — Thân thiện", "NEUTRAL", "multilingual"),
        "gemini:Zubenelgenubi" to Triple("Gemini Zubenelgenubi — Tự nhiên", "NEUTRAL", "multilingual"),
        "gemini:Vindemiatrix" to Triple("Gemini Vindemiatrix — Nhẹ nhàng", "NEUTRAL", "multilingual"),
        "gemini:Sadachbia" to Triple("Gemini Sadachbia — Sinh động", "NEUTRAL", "multilingual"),
        "gemini:Sadaltager" to Triple("Gemini Sadaltager — Am hiểu", "NEUTRAL", "multilingual"),
        "gemini:Sulafat" to Triple("Gemini Sulafat — Ấm áp", "NEUTRAL", "multilingual"),
    )

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override val voiceList: List<JsonObject>
        get() = if (!isConfigured()) emptyList() else voices.map { (id, info) ->
            TtsUtil.voice(id, info.first, info.second, "Gemini AI", info.third, "Gemini", "gemini", true)
        }

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? {
        lastError = null
        if (!isConfigured()) { lastError = "Chưa cấu hình API key Gemini"; return null }
        val fullKey = if (voiceId.startsWith("gemini:")) voiceId else "gemini:$voiceId"
        if (!voices.containsKey(fullKey) && !voices.containsKey(voiceId)) {
            lastError = "Giọng Gemini không hỗ trợ: $voiceId"
            return null
        }
        val clean = text.trim()
        if (clean.isEmpty()) { lastError = "Đoạn văn bản rỗng"; return null }
        val voiceName = fullKey.removePrefix("gemini:").ifBlank { voiceId.removePrefix("gemini:") }

        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject { putJsonArray("parts") { addJsonObject { put("text", clean) } } }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", voiceName) }
                    }
                }
            }
        }

        return withContext(Dispatchers.IO) {
            var lastCode: Int? = null
            for (model in models) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                for (attempt in 0 until maxRetries) {
                    try {
                        val req = Request.Builder().url(url).post(body.toString().toRequestBody(jsonMedia)).build()
                        TtsUtil.http.newCall(req).execute().use { resp ->
                            lastCode = resp.code
                            if (resp.code == 429) {
                                delay((Math.pow(2.0, attempt.toDouble()) * 1000).toLong()); return@use
                            }
                            if (resp.code == 404) return@use // thử model khác
                            if (!resp.isSuccessful) {
                                if (resp.code in listOf(400, 401, 403)) {
                                    lastError = when (resp.code) {
                                        401, 403 -> "API key Gemini không hợp lệ hoặc bị từ chối"
                                        else -> "Gemini từ chối yêu cầu (HTTP ${resp.code})"
                                    }
                                    return@withContext null
                                }
                                return@use
                            }
                            val txt = resp.body?.string() ?: return@use
                            val audio = extractAudio(txt)
                            if (audio != null) return@withContext audio
                            lastError = "Gemini trả về không có audio"
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: "Lỗi mạng Gemini"
                        delay((Math.pow(2.0, attempt.toDouble()) * 1000).toLong())
                    }
                }
            }
            if (lastError == null) {
                lastError = when (lastCode) {
                    404 -> "Model Gemini TTS không còn khả dụng"
                    429 -> "Gemini đang giới hạn tần suất"
                    else -> "Gemini TTS thất bại"
                }
            }
            null
        }
    }

    private fun extractAudio(responseText: String): ByteArray? {
        return try {
            val root = Json.parseToJsonElement(responseText).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return null
            if (candidates.isEmpty()) return null
            val parts = candidates[0].jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null
            if (parts.isEmpty()) return null
            val inline = parts[0].jsonObject["inlineData"]?.jsonObject ?: return null
            val b64 = inline["data"]?.jsonPrimitive?.contentOrNull ?: return null
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            val raw = Base64.getDecoder().decode(b64)
            if (raw.size >= 4 && raw[0].toInt() == 'R'.code && raw[1].toInt() == 'I'.code) raw // da la WAV
            else pcmToWav(raw, 24000, 1, 2)
        } catch (e: Exception) { null }
    }

    /** Bao PCM Linear16 thanh WAV. */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, sampleWidth: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val byteRate = sampleRate * channels * sampleWidth
        val dataLen = pcm.size
        val totalLen = 36 + dataLen
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i4(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i2(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        w("RIFF"); i4(totalLen); w("WAVE"); w("fmt "); i4(16); i2(1); i2(channels)
        i4(sampleRate); i4(byteRate); i2(channels * sampleWidth); i2(sampleWidth * 8)
        w("data"); i4(dataLen); out.write(pcm)
        return out.toByteArray()
    }
}
