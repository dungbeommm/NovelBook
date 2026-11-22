package vn.tramtruyen.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CapcutVietnameseNormalizerTest {
    @Test
    fun removesInvisibleCharacterBeforeNfcComposition() {
        assertEquals("người", CapcutVietnameseNormalizer.prepareForSsml("ng\u200Bười"))
        assertEquals("người", CapcutVietnameseNormalizer.prepareForSsml("ng\u00ADười"))
    }

    @Test
    fun normalizesUnicodeSpacesAndLineBreaks() {
        assertEquals(
            "dòng một, dòng hai. dòng ba",
            CapcutVietnameseNormalizer.prepareForSsml("dòng\u00A0một\r\ndòng hai\n\ndòng ba"),
        )
    }

    @Test
    fun keepsDecimalCommaAndAddsSentenceSpacing() {
        assertEquals(
            "được 3,5 điểm! Rồi đi.",
            CapcutVietnameseNormalizer.prepareForSsml("được 3,5 điểm!Rồi đi!!"),
        )
    }

    @Test
    fun removesRepeatedCombiningMarksBeforeNfc() {
        assertEquals("à", CapcutVietnameseNormalizer.prepareForSsml("a\u0300\u0300"))
    }
}