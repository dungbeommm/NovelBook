package vn.tramtruyen.reader.engine.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * Google Translate TTS (gTTS) — mien phi, khong can API key.
 * Dung endpoint translate_tts voi client=tw-ob (khong can token).
 * voice_id: "vi" (tld com) hoac "vi-tld-com.vn" (tld com.vn).
 */
class GttsProvider : TtsProvider {

    private val tldMap = mapOf("vi" to "com", "vi-tld-com.vn" to "com.vn")
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    override val voiceList: List<JsonObject> = listOf(
        TtsUtil.voice("gtts:vi", "Google Translate \u2014 Ti\u1ebfng Vi\u1ec7t", "FEMALE", "Standard", "vi-VN", "Google TTS (Free)", "gtts", false),
        TtsUtil.voice("gtts:vi-tld-com.vn", "Google TTS \u2014 Ti\u1ebfng Vi\u1ec7t (VN)", "FEMALE", "Standard", "vi-VN", "Google TTS (Free)", "gtts", false),
    )

    override fun isConfigured(): Boolean = true

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val id = voiceId.removePrefix("gtts:")
        val tld = tldMap[id] ?: "com"
        val slow = TtsUtil.clampSpeed(speed) < 0.8
        val ttsSpeed = if (slow) "0.24" else "1"
        val chunks = splitText(clean, 100)
        val total = chunks.size

        return withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            for ((idx, chunk) in chunks.withIndex()) {
                val q = URLEncoder.encode(chunk, "UTF-8")
                val url = "https://translate.google.$tld/translate_tts?ie=UTF-8&q=$q&tl=vi&total=$total&idx=$idx" +
                    "&textlen=${chunk.length}&client=tw-ob&ttsspeed=$ttsSpeed&tld=$tld"
                val req = Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Referer", "https://translate.google.$tld/")
                    .build()
                try {
                    TtsUtil.http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val body = resp.body?.bytes() ?: return@withContext null
                        out.write(body)
                    }
                } catch (e: Exception) { return@withContext null }
            }
            val data = out.toByteArray()
            if (data.isEmpty()) null else data
        }
    }

    /** Tokenizer don gian: tach theo dau cau, moi doan <= maxLen ky tu. */
    private fun splitText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val result = ArrayList<String>()
        var remaining = text
        val seps = listOf(". ", ".\n", "! ", "? ", "; ", ", ", " ")
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) { result.add(remaining.trim()); break }
            var splitAt = -1
            for (sep in seps) {
                val idx = remaining.lastIndexOf(sep, maxLen)
                if (idx > maxLen / 3) { splitAt = idx + sep.length; break }
            }
            if (splitAt < 0) splitAt = maxLen
            result.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trimStart()
        }
        return result.filter { it.isNotEmpty() }
    }
}
