package vn.tramtruyen.reader.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cover/color helpers. Remote Unsplash fallbacks removed — empty coverUrl means
 * the Compose UI shows a local gradient + monogram (instant, offline).
 */
object Covers {

    val FALLBACK_COLORS = listOf(
        "#8b5cf6", "#14b8a6", "#f43f5e", "#eab308",
        "#ec4899", "#3b82f6", "#10b981", "#f97316"
    )

    private fun hash(text: String): Long {
        var h = 0L
        for (ch in text) {
            h = (ch.code.toLong() + ((h shl 5) - h)) and 0xFFFFFFFFL
        }
        return h
    }

    /** No remote fallback — UI paints gradient locally. */
    fun coverFor(name: String): String = ""
    fun colorFor(name: String): String = FALLBACK_COLORS[(hash(name) % FALLBACK_COLORS.size).toInt()]

    private fun str(el: JsonElement?): String? =
        if (el == null || el is JsonNull) null else (el as? JsonPrimitive)?.contentOrNull

    fun ensureCover(item: MutableMap<String, JsonElement>) {
        val name = str(item["title"]) ?: str(item["folder"]) ?: str(item["id"]) ?: ""
        // Keep existing remote cover if present; otherwise leave blank for local gradient.
        if (str(item["coverUrl"]).isNullOrEmpty()) item["coverUrl"] = JsonPrimitive("")
        if (str(item["color"]).isNullOrEmpty()) item["color"] = JsonPrimitive(colorFor(name))
    }
}
