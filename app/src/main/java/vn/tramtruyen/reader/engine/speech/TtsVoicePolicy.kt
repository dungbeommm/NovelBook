package vn.tramtruyen.reader.engine.speech

/** Voice-key validation and migration for the supported speech providers. */
object TtsVoicePolicy {
    const val DEFAULT_VOICE = "gtts:vi"

    const val NANO_V4_MODEL = "vieneu-v3-nano-v4"
    private val oldNanoModels = setOf("vieneu-v3-nano", "vieneu-v3-nano-v2", "vieneu-v3-nano-v3")

    private val providers = setOf("android", "edge", "capcut", "gtts", "gemini", "zalo", "tiktok", "vbook")

    fun migrate(key: String?): String {
        if (key.isNullOrBlank()) return DEFAULT_VOICE
        val separator = key.indexOf(':')
        if (separator < 1 || separator == key.lastIndex) return DEFAULT_VOICE
        val parts = key.split(':', limit = 3)
        if (parts.size == 3 && parts[0].equals("vbook", ignoreCase = true) && parts[1] in oldNanoModels) {
            val speaker = parts[2].toIntOrNull()?.takeIf { it in 0..<29 } ?: return DEFAULT_VOICE
            return "vbook:$NANO_V4_MODEL:$speaker"
        }
        return if (key.substring(0, separator).lowercase() in providers) key else DEFAULT_VOICE
    }
}
