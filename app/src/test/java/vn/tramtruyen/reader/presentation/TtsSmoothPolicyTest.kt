package vn.tramtruyen.reader.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSmoothPolicyTest {
    @Test
    fun keepsEachCompleteSentenceSeparate() {
        val text = buildString {
            repeat(18) { index -> append("Câu số $index có nội dung tiếng Việt tự nhiên. ") }
        }
        val chunks = splitIntoChunks(text)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= SpeechAudioPolicy.MAX_CHUNK })
        assertEquals(18, chunks.size)
        assertEquals("Câu số 0 có nội dung tiếng Việt tự nhiên.", chunks.first())
    }

    @Test
    fun keepsAbbreviationsDecimalsAndDialoguePunctuation() {
        assertEquals(
            listOf("“Chào ông, TS. An!”", "Ông trả 3.14 đồng.", "他来了。", "她点头！"),
            splitIntoChunks("“Chào ông, TS. An!” Ông trả 3.14 đồng. 他来了。她点头！"),
        )
    }

    @Test
    fun breaksOversizedSentencesAtWhitespace() {
        val text = "Diệp Phong " + "tiếp tục đi qua nơi ấy ".repeat(40) + "rồi dừng lại."
        val chunks = splitIntoChunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= SpeechAudioPolicy.MAX_CHUNK })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun usesNaturalPauseByPunctuation() {
        assertEquals(24, SpeechAudioPolicy.tailSilenceMs("Kết thúc."))
        assertEquals(10, SpeechAudioPolicy.tailSilenceMs("Tiếp theo,"))
        assertEquals(0, SpeechAudioPolicy.tailSilenceMs("không dấu"))
    }

    @Test fun prefetchFirstChunkMatchesPlaybackForShortAndLongChapters() {
        val short = "x".repeat(228) + ". Câu cuối."
        val large = ("a".repeat(239) + "😀").repeat(1_000)
        for (text in listOf(short, large)) {
            val chapters = splitIntoChunks(text)
            assertEquals(chapters.first(), splitIntoChunks(text, firstOnly = true).single())
            assertTrue(chapters.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        }
    }
}
