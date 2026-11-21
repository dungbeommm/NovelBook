package vn.tramtruyen.reader.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPreferencesTest {
    @Test
    fun autoDetectsVietnameseAndEnglish() {
        ReadingPreferences.update("AUTO", "SMART")
        assertTrue(ReadingPreferences.shouldNormalizeVietnamese("Chương mới đã bắt đầu ở nơi này."))
        assertFalse(ReadingPreferences.shouldNormalizeVietnamese("The chapter was long, but the hero had not returned."))
    }

    @Test
    fun explicitLanguageOverridesDetection() {
        ReadingPreferences.update("ENGLISH", "SMART")
        assertFalse(ReadingPreferences.shouldNormalizeVietnamese("Chương một"))
        ReadingPreferences.update("VIETNAMESE", "SMART")
        assertTrue(ReadingPreferences.shouldNormalizeVietnamese("Chapter one"))
    }
}