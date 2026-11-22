package vn.tramtruyen.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPipelineTest {
    @Test
    fun normalizeNumbersAndPercent() {
        val result = TextPipeline.normalize("50% va 3,5")
        assertTrue(result.contains("năm mươi phần trăm"))
        assertTrue(result.contains("ba phẩy năm"))
    }

    @Test
    fun decimalPercentIsNotCollapsedIntoInteger() {
        assertEquals("một phẩy năm phần trăm", TextPipeline.normalize("1,5%"))
    }

    @Test
    fun rangeAndMeasurementAreSpokenNaturally() {
        assertEquals(
            "Từ ba đến năm ki lô gam, giảm mười hai phẩy năm phần trăm.",
            TextPipeline.normalize("Từ 3-5 kg, giảm 12,5%.")
        )
    }

    @Test
    fun dateAndTimeAreNormalizedBeforeGeneralNumbers() {
        assertEquals(
            "Hẹn lúc bảy giờ lẻ năm phút ngày năm tháng tám năm hai nghìn không trăm hai mươi sáu.",
            TextPipeline.normalize("Hẹn lúc 07:05 ngày 05/08/2026.")
        )
    }

    @Test
    fun phoneKeepsLeadingZeros() {
        assertEquals(
            "Gọi không chín không một hai ba bốn năm sáu bảy.",
            TextPipeline.normalize("Gọi 0901 234 567.")
        )
    }

    @Test
    fun moneyMultiplierAndLargeScaleAreReadCorrectly() {
        assertEquals(
            "Giá một triệu hai trăm năm mươi nghìn đồng, hai phẩy năm triệu.",
            TextPipeline.normalize("Giá 1.250.000đ, 2,5 triệu.")
        )
        assertEquals("một nghìn tỷ", VietNormalizer.readInteger("1000000000000"))
    }

    @Test
    fun romanNumeralsAndAcronymsAreExpanded() {
        assertEquals(
            "Chương bốn, Thế kỷ hai mươi mốt. xê pê u, bốn ca và trí tuệ nhân tạo.",
            TextPipeline.normalize("Chương IV - Thế kỷ XXI. CPU, 4K và AI.")
        )
    }

    @Test
    fun capcutPipelineCleansInvisibleCharactersAndLineBreaks() {
        assertEquals(
            "Xin chào, thế giới.",
            TextPipeline.normalizeForTts("Xin\u200B chào\nthế giới.", "capcut:vi_female_huong")
        )
    }

    @Test
    fun cleanExtractedTextRemovesSpamLinksAndHiddenDots() {
        val result = TextPipeline.cleanExtracted(
            "Nội dung ch.ư.ơ.ng thật\nchương tiếp\nXem https://example.com ngay"
        )
        assertTrue(result.contains("chương"))
        assertFalse(result.contains("https://"))
    }

    @Test
    fun stripLeadingTitle() {
        assertEquals(
            "Noi dung",
            TextPipeline.stripLeadingTitles(
                "Chuong 1: Mo dau\nNoi dung",
                listOf("Chuong 1: Mo dau")
            )
        )
    }
}
