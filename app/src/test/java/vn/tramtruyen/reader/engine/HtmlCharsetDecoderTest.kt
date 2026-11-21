package vn.tramtruyen.reader.engine

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlCharsetDecoderTest {
    @Test
    fun decodesCharsetFromMetaWhenHeaderOmitsIt() {
        val html = """<meta charset="ISO-8859-1"><p>café</p>"""
            .toByteArray(StandardCharsets.ISO_8859_1)

        assertEquals(
            """<meta charset="ISO-8859-1"><p>café</p>""",
            HtmlCharsetDecoder.decode(html, "text/html"),
        )
    }

    @Test
    fun httpCharsetTakesPrecedenceOverMeta() {
        val html = "<meta charset=\"ISO-8859-1\"><p>Xin chào</p>"
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals(
            "<meta charset=\"ISO-8859-1\"><p>Xin chào</p>",
            HtmlCharsetDecoder.decode(html, "text/html; charset=utf-8"),
        )
    }
}