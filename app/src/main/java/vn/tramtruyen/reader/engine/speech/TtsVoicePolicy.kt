package vn.tramtruyen.reader.engine.speech

/** Voice-key validation and migration for the supported speech providers. */
object TtsVoicePolicy {
    const val DEFAULT_VOICE = "gtts:vi"

    private val providers = setOf("android", "edge", "capcut", "gtts", "gemini", "zalo", "tiktok", "vbook")

    fun migrate(key: String?): String {
        if (key.isNullOrBlank()) return DEFAULT_VOICE
        val separator = key.indexOf(':')
        if (separator < 1 || separator == key.lastIndex) return DEFAULT_VOICE
        return if (key.substring(0, separator).lowercase() in providers) key else DEFAULT_VOICE
    }
}
