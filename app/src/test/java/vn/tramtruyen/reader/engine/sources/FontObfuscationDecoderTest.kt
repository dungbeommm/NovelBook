package vn.tramtruyen.reader.engine.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiem chung bo giai ma font chong crawl (co che characterMap cua AI NovelReader). */
class FontObfuscationDecoderTest {

    private val map = mapOf(0xE402 to "月", 0xE4F3 to "的", 0xE444 to "着")

    @Test
    fun decodesPuaCharsByMap() {
        val raw = "\uE402\uE4F3光\uE444"
        assertEquals("月的光着", FontObfuscationDecoder.decode(raw, map))
    }

    @Test
    fun keepsUnmappedPuaAndNormalText() {
        val raw = "Chương 1 \uE777 mở đầu"
        assertEquals(raw, FontObfuscationDecoder.decode(raw, map))
        assertEquals("abc", FontObfuscationDecoder.decode("abc", map))
    }

    @Test
    fun detectsObfuscatedChars() {
        assertTrue(FontObfuscationDecoder.hasObfuscatedChars("truyện \uE402"))
        assertFalse(FontObfuscationDecoder.hasObfuscatedChars("truyện bình thường"))
    }
}
