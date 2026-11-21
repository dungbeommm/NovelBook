package vn.tramtruyen.reader.engine

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Decodes HTML bytes using the HTTP charset, BOM, or an HTML meta declaration. */
internal object HtmlCharsetDecoder {
    private val headerCharset = Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)
    private val metaTags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val metaCharset = Regex("""\bcharset\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""

        val (bomCharset, offset) = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> Charset.forName("UTF-32BE") to 4
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> Charset.forName("UTF-32LE") to 4
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8 to 3
            bytes.startsWith(0xFE, 0xFF) -> StandardCharsets.UTF_16BE to 2
            bytes.startsWith(0xFF, 0xFE) -> StandardCharsets.UTF_16LE to 2
            else -> null to 0
        }

        val prefix = String(
            bytes.copyOfRange(offset, minOf(bytes.size, offset + 8192)),
            StandardCharsets.ISO_8859_1,
        )
        val declared = headerCharset.find(contentType.orEmpty())?.groupValues?.getOrNull(1)
            ?: metaTags.findAll(prefix).firstNotNullOfOrNull { tag ->
                metaCharset.find(tag.value)?.groupValues?.getOrNull(1)
            }
        val charset = bomCharset ?: declared?.let {
            runCatching { Charset.forName(it) }.getOrNull()
        } ?: StandardCharsets.UTF_8
        return String(bytes, offset, bytes.size - offset, charset)
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }
}