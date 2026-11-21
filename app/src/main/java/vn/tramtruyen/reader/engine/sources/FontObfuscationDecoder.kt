package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GIAI MA FONT CHONG CRAWL kieu Fanqie/Tomato — port co che characterMap cua
 * AI NovelReader: web thay chu that bang ky tu vung rieng (PUA U+E000..U+F8FF)
 * roi nhung font rieng de trinh duyet ve dung; text trich bang DOM chi la ma
 * PUA vo nghia. Bang anh xa dong goi tai assets/font_maps/fanqie.json dang
 * {ky tu PUA: chu that} (321 muc). Ky tu PUA khong co trong bang thi giu nguyen
 * (giong hanh vi ky tu '?' cua app goc).
 */
object FontObfuscationDecoder {
    private const val ASSET_PATH = "font_maps/fanqie.json"

    @Volatile private var cached: Map<Int, String>? = null

    private fun loadMap(): Map<Int, String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                val ctx = Config.appContext ?: return emptyMap()
                val text = ctx.assets.open(ASSET_PATH)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val obj = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
                val out = HashMap<Int, String>(obj.size)
                for ((k, v) in obj) {
                    if (k.isEmpty()) continue
                    val rep = (v as? JsonPrimitive)?.contentOrNull ?: continue
                    if (rep.isNotEmpty()) out[k.codePointAt(0)] = rep
                }
                out
            } catch (e: Exception) { emptyMap() }
            cached = parsed
            return parsed
        }
    }

    /** Text co chua ky tu PUA (dau hieu bi ma hoa bang font) hay khong. */
    fun hasObfuscatedChars(text: String): Boolean =
        text.any { it.code in 0xE000..0xF8FF }

    /** Giai ma bang bang anh xa dong goi san. */
    fun decode(text: String): String {
        if (!hasObfuscatedChars(text)) return text
        return decode(text, loadMap())
    }

    /** Giai ma voi bang anh xa chi dinh (de kiem thu). */
    fun decode(text: String, map: Map<Int, String>): String {
        if (map.isEmpty() || !hasObfuscatedChars(text)) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val rep = map[cp]
            if (rep != null) sb.append(rep) else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
