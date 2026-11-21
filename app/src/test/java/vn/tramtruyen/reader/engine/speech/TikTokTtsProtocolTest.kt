package vn.tramtruyen.reader.engine.speech

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TikTokTtsProtocolTest {
    private val fakeMp3 = ByteArray(256).also { it[0] = 0xff.toByte(); it[1] = 0xf3.toByte(); it[2] = 0xe4.toByte() }
    private fun response(bytes: ByteArray = fakeMp3): String = buildJsonObject {
        put("success", true); put("data", Base64.getEncoder().encodeToString(bytes)); put("error", JsonNull)
    }.toString()

    @Test fun encodesVietnameseQuotesAndNewlinesWithoutUrlEscaping() {
        val text = "Cô ấy nói: \"Xin chào & hẹn gặp lại\".\nDòng hai."
        val body = Json.parseToJsonElement(TikTokTtsProtocol.requestBody(text, "tiktok:BV074_streaming")).jsonObject
        assertEquals(text, body["text"]!!.jsonPrimitive.content)
        assertEquals("BV074_streaming", body["voice"]!!.jsonPrimitive.content)
    }
    @Test fun acceptsExactly300Characters() {
        assertTrue(TikTokTtsProtocol.requestBody("a".repeat(300), "BV075_streaming").isNotEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsLongTextInsteadOfTruncating() {
        TikTokTtsProtocol.requestBody("a".repeat(301), "BV074_streaming")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBlankText() {
        TikTokTtsProtocol.requestBody(" \n ", "BV074_streaming")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVoice() {
        TikTokTtsProtocol.requestBody("Xin chào", "unknown")
    }
    @Test fun decodesFrameSyncMp3() { assertArrayEquals(fakeMp3, TikTokTtsProtocol.decodeResponse(response())) }
    @Test fun decodesId3Mp3() {
        val audio = ByteArray(256).also { it[0] = 73; it[1] = 68; it[2] = 51 }
        assertArrayEquals(audio, TikTokTtsProtocol.decodeResponse(response(audio)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFailedEnvelopeEvenWithAudio() {
        TikTokTtsProtocol.decodeResponse(response().replace("\"success\":true", "\"success\":false"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingAudio() {
        TikTokTtsProtocol.decodeResponse("{\"success\":true,\"data\":null}")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidBase64() {
        TikTokTtsProtocol.decodeResponse("{\"success\":true,\"data\":\"%%%bad\"}")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsHtmlResponse() {
        TikTokTtsProtocol.decodeResponse("<html>Blocked</html>")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonAudioBase64() {
        TikTokTtsProtocol.decodeResponse(response("<html>no audio</html>".repeat(20).toByteArray()))
    }
    @Test fun classifiesRateLimitAndRedirect() {
        assertTrue(TikTokTtsProtocol.httpError(429).contains("429"))
        assertTrue(TikTokTtsProtocol.httpError(307).contains("endpoint"))
    }
    @Test fun exposesTwoVietnameseVoicesWithoutChangingOtherProviders() {
        val vi = TikTokTtsProtocol.voices.filter { it.language == "vi-VN" }
        assertEquals(listOf("BV074_streaming", "BV075_streaming"), vi.map { it.id })
        assertEquals("tiktok:BV075_streaming", OfflineVoicePolicy.migrate("tiktok:BV075_streaming"))
        assertEquals("gtts:vi", OfflineVoicePolicy.migrate("gtts:vi"))
        assertEquals("offline:mytam2", OfflineVoicePolicy.migrate("offline:mytam2"))
    }
}
