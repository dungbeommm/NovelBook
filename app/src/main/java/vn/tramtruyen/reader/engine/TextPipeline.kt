package vn.tramtruyen.reader.engine

/**
 * Diem vao thong nhat cho xu ly van ban thuan Kotlin.
 *
 * Giu facade nay de cac call site khong phu thuoc truc tiep vao tung bo xu ly,
 * dong thoi cho phep toi uu/kiem thu pipeline tai mot noi.
 */
object TextPipeline {

    /** Chuẩn hóa văn bản tiếng Việt dùng chung cho TTS. */
    fun normalize(input: String): String =
        if (ReadingPreferences.shouldNormalizeVietnamese(input)) VietNormalizer.normalize(input)
        else input.trim()

    /** Chuẩn hóa theo provider; CapCut có thêm lớp làm sạch SSML riêng. */
    fun normalizeForTts(input: String, voiceId: String?): String =
        if (voiceId?.startsWith("capcut:", ignoreCase = true) == true) {
            CapcutVietnameseNormalizer.normalize(input)
        } else if (!ReadingPreferences.shouldNormalizeVietnamese(input)) {
            input.trim()
        } else {
            VietNormalizer.normalize(input)
        }

    /** Tach am tiet va gop tu ghep tieng Viet. */
    fun segment(input: String): String = VietWordSegmenter.segment(input)

    /** Lam sach text sau khi Jsoup da boc tach DOM. */
    fun cleanExtracted(text: String): String = Cleaner.cleanExtractedText(text)

    /** Cat tieu de lap o dau chuong. */
    fun stripLeadingTitles(text: String, dropTitles: List<String>): String =
        Cleaner.stripLeadingTitles(text, dropTitles)
}
