package vn.tramtruyen.reader.engine.speech

/** Preserve existing providers, voice IDs, per-book profiles and imported backups. */
object OfflineVoicePolicy {
    const val DEFAULT_VOICE = "gtts:vi"
    private val providers = setOf("android", "edge", "capcut", "gtts", "gemini", "zalo", "offline", "tiktok")
    fun migrate(key: String?): String {
        if (key.isNullOrBlank()) return DEFAULT_VOICE
        val separator = key.indexOf(':')
        if (separator < 1 || separator == key.lastIndex) return DEFAULT_VOICE
        return if (key.substring(0, separator) in providers) key else DEFAULT_VOICE
    }
}
