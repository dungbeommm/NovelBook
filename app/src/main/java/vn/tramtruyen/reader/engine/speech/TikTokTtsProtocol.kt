package vn.tramtruyen.reader.engine.speech

import java.util.Base64
import kotlinx.serialization.json.*

/** Public Weilbyte JSON proxy contract. No TikTok account cookies are sent. */
internal object TikTokTtsProtocol {
    const val ENDPOINT = "https://ottsy.weilbyte.dev/api/generation"
    const val MAX_TEXT_LENGTH = 300
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    data class Voice(val id: String, val name: String, val gender: String, val language: String)
    val voices = listOf(
        Voice("BV074_streaming", "TikTok · Tiếng Việt nữ", "FEMALE", "vi-VN"),
        Voice("BV075_streaming", "TikTok · Tiếng Việt nam", "MALE", "vi-VN"),
        Voice("en_us_001", "TikTok · English US nữ", "FEMALE", "en-US"),
        Voice("en_us_006", "TikTok · English US nam", "MALE", "en-US"),
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun requestBody(text: String, voiceId: String): String {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "TikTok TTS: đoạn văn bản rỗng" }
        require(clean.length <= MAX_TEXT_LENGTH) {
            "TikTok TTS: đoạn dài quá $MAX_TEXT_LENGTH ký tự; hãy chia đoạn, không cắt bỏ nội dung"
        }
        val id = voiceId.removePrefix("tiktok:")
        require(voices.any { it.id == id }) { "TikTok TTS: mã giọng không được hỗ trợ" }
        return buildJsonObject { put("text", clean); put("voice", id) }.toString()
    }

    fun decodeResponse(body: String): ByteArray {
        require(body.length <= MAX_RESPONSE_BYTES) { "TikTok TTS: phản hồi vượt giới hạn an toàn" }
        val root = try { json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw IllegalArgumentException("TikTok TTS: dịch vụ không trả JSON hợp lệ") }
        val succeeded = (root["success"] as? JsonPrimitive)?.booleanOrNull
        require(succeeded != false) { "TikTok TTS: dịch vụ không tạo được âm thanh; hãy thử lại sau hoặc chọn giọng khác" }
        val encoded = (root["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(!encoded.isNullOrBlank()) { "TikTok TTS: dịch vụ trả dữ liệu âm thanh rỗng" }
        val audio = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("TikTok TTS: dữ liệu Base64 bị lỗi") }
        require(audio.size >= 128 && isMp3(audio)) { "TikTok TTS: phản hồi không phải âm thanh MP3 hợp lệ" }
        return audio
    }

    fun isMp3(audio: ByteArray): Boolean {
        if (audio.size < 3) return false
        if (audio[0] == 'I'.code.toByte() && audio[1] == 'D'.code.toByte() && audio[2] == '3'.code.toByte()) return true
        val a = audio[0].toInt() and 255
        val b = audio[1].toInt() and 255
        return a == 255 && b and 224 == 224 && b and 24 != 8 && b and 6 != 0
    }

    fun httpError(code: Int): String = when (code) {
        429 -> "TikTok TTS đang giới hạn yêu cầu (HTTP 429); hãy đợi rồi thử lại"
        401, 403 -> "Dịch vụ TikTok TTS từ chối truy cập (HTTP $code); không tự chuyển sang giọng khác"
        in 300..399 -> "Địa chỉ dịch vụ TikTok TTS đã thay đổi (HTTP $code); cần cập nhật endpoint"
        else -> "Dịch vụ TikTok TTS không phản hồi thành công (HTTP $code)"
    }
}
