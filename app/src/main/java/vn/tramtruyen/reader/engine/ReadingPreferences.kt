package vn.tramtruyen.reader.engine

/**
 * Tuỳ chọn đọc dùng chung cho HTTP, WebView, bộ trích DOM và TTS.
 * Chỉ giữ giá trị nhẹ trong bộ nhớ; SettingsStore chịu trách nhiệm lưu bền vững.
 */
enum class ReadingLanguage {
    AUTO, VIETNAMESE, ENGLISH, CHINESE;

    fun acceptLanguageHeader(): String = when (this) {
        AUTO -> "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7,zh-CN;q=0.5"
        VIETNAMESE -> "vi-VN,vi;q=0.9,en;q=0.6"
        ENGLISH -> "en-US,en;q=0.9,vi;q=0.5"
        CHINESE -> "zh-CN,zh;q=0.9,vi;q=0.5,en;q=0.4"
    }
}

enum class ContentExtractionMode {
    /** So sánh parser của nguồn và bộ trích thông minh, chọn kết quả sạch/đầy đủ hơn. */
    SMART,
    /** Ưu tiên selector chính xác của nguồn; chỉ fallback khi kết quả gần như rỗng. */
    SOURCE_FIRST,
    /** Ưu tiên phân tích DOM tổng quát, hữu ích với website chưa có parser. */
    GENERIC_FIRST,
}

object ReadingPreferences {
    @Volatile var language: ReadingLanguage = ReadingLanguage.AUTO
        private set
    @Volatile var extractionMode: ContentExtractionMode = ContentExtractionMode.SMART
        private set

    fun update(languageName: String, extractionModeName: String) {
        language = runCatching { ReadingLanguage.valueOf(languageName) }
            .getOrDefault(ReadingLanguage.AUTO)
        extractionMode = runCatching { ContentExtractionMode.valueOf(extractionModeName) }
            .getOrDefault(ContentExtractionMode.SMART)
    }

    fun acceptLanguageHeader(): String = language.acceptLanguageHeader()

    fun shouldNormalizeVietnamese(text: String): Boolean = when (language) {
        ReadingLanguage.VIETNAMESE -> true
        ReadingLanguage.ENGLISH, ReadingLanguage.CHINESE -> false
        ReadingLanguage.AUTO -> {
            if (text.any { it.code in 0x3400..0x9FFF }) {
                false
            } else {
                val lower = text.lowercase()
                val viMarks = lower.count { it in "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ" }
                if (viMarks >= 2) {
                    true
                } else {
                    val words = Regex("[a-z']+").findAll(lower).map { it.value }.toList()
                    val englishHints = setOf(
                        "the", "and", "that", "this", "with", "from", "chapter", "you",
                        "was", "were", "have", "had", "not", "but", "for", "his", "her",
                    )
                    words.count { it in englishHints } < 3
                }
            }
        }
    }
}