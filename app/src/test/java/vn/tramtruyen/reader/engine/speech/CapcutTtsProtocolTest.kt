package vn.tramtruyen.reader.engine.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapcutTtsProtocolTest {
    @Test
    fun acceptsBothObservedSuccessSpellings() {
        assertEquals(CapcutTaskState.SUCCEEDED, CapcutTtsProtocol.classifyStatus("succeed"))
        assertEquals(CapcutTaskState.SUCCEEDED, CapcutTtsProtocol.classifyStatus("success"))
        assertEquals(CapcutTaskState.SUCCEEDED, CapcutTtsProtocol.classifyStatus("completed"))
    }

    @Test
    fun classifiesWaitingAndFailureStates() {
        assertEquals(CapcutTaskState.WAITING, CapcutTtsProtocol.classifyStatus("queueing"))
        assertEquals(CapcutTaskState.WAITING, CapcutTtsProtocol.classifyStatus("processing"))
        assertEquals(CapcutTaskState.FAILED, CapcutTtsProtocol.classifyStatus("failed"))
    }

    @Test
    fun exposesSharkBlockInsteadOfSwallowingIt() {
        val message = CapcutTtsProtocol.apiError("-6", "shark block only")
        assertTrue(message.orEmpty().contains("ret=-6"))
        assertNull(CapcutTtsProtocol.apiError("0", "ok"))
    }

    @Test
    fun validatesStableDesktopDeviceIdShape() {
        assertTrue(CapcutTtsProtocol.isValidDeviceId("76471456455646328721"))
        assertFalse(CapcutTtsProtocol.isValidDeviceId("7000000000000000000"))
        assertFalse(CapcutTtsProtocol.isValidDeviceId("76abc456455646328721"))
    }

    @Test
    fun detectsTruncatedAudioWithoutRejectingShortSentences() {
        assertTrue(CapcutTtsProtocol.isSuspiciouslyShort(200, 1_000L))
        assertFalse(CapcutTtsProtocol.isSuspiciouslyShort(200, 5_000L))
        assertFalse(CapcutTtsProtocol.isSuspiciouslyShort(40, 300L))
    }

    @Test fun triesMergedAndAlternateAudioUrlsFromStringPayloads() {
        val task = Json.parseToJsonElement("""
            {"payload":"{\"audio_url\":\"https://example.com/primary.mp3\",\"output\":{\"merged_audio_url\":\"https://example.com/merged\",\"download_url\":\"https://example.com/backup.mp3\"}}"}
        """.trimIndent()).jsonObject
        assertEquals(listOf(
            "https://example.com/merged",
            "https://example.com/primary.mp3",
            "https://example.com/backup.mp3",
        ), CapcutTtsProtocol.audioUrls(task))
    }

    @Test fun ignoresSubtitlesAndMultipleAudioSegments() {
        val task = Json.parseToJsonElement("""
            {"payload":{"audio_subtitles":[{"url":"https://example.com/sub.srt"}],
              "audio_list":[{"url":"https://example.com/one.mp3"},{"url":"https://example.com/two.mp3"}]}}
        """.trimIndent()).jsonObject
        assertTrue(CapcutTtsProtocol.audioUrls(task).isEmpty())
        assertFalse(CapcutTtsProtocol.looksLikeSupportedAudio("RIFF0000WEBP".toByteArray()))
        assertTrue(CapcutTtsProtocol.looksLikeSupportedAudio("RIFF0000WAVE".toByteArray()))
    }

    @Test fun findsSpeechInObservedAudioSubtitleRecord() {
        val task = Json.parseToJsonElement("""
            {"status":"succeed","payload":"{\"audio_subtitles\":[{\"speech_url\":\"https://example.com/opaque-audio\",\"url\":\"https://example.com/words.srt\"}]}"}
        """.trimIndent()).jsonObject
        assertEquals(listOf("https://example.com/opaque-audio"), CapcutTtsProtocol.audioUrls(task))
    }
}
