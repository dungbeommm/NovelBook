package vn.tramtruyen.reader.engine.ebooks

import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private lateinit var directory: File

    @Before fun setup() {
        directory = Files.createTempDirectory("epub-parser").toFile()
        Config.init(directory)
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    private fun makeBook(firstPage: String, secondPage: String, coverInSpine: Boolean = false): File {
        val opf = """
            <package xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata><dc:title>Sách thử</dc:title><dc:creator>Tác giả</dc:creator></metadata>
              <manifest>
                <item id="second" href="hai.xhtml" media-type="application/xhtml+xml"/>
                <item id="first" href="ch%C6%B0%C6%A1ng+1.xhtml#start" media-type="application/xhtml+xml"/>
                <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>${if (coverInSpine) """<itemref idref="cover"/>""" else ""}
                <itemref idref="first"/><itemref idref="second"/>
              </spine>
            </package>
        """.trimIndent()
        val book = File(directory, "test.epub")
        ZipOutputStream(book.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray(),
                "book.opf" to opf.toByteArray(),
                "chương+1.xhtml" to firstPage.toByteArray(Charsets.UTF_16),
                "hai.xhtml" to secondPage.toByteArray(),
                "cover.xhtml" to """<html><head><title>Bìa</title></head><body><img src="cover.jpg"/></body></html>""".toByteArray(),
            )
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // An unused resource over the limit for text entries must not be inflated.
            zip.putNextEntry(ZipEntry("unused.mp4"))
            val block = ByteArray(1024 * 1024)
            repeat(17) { zip.write(block) }
            zip.closeEntry()
        }
        return book
    }

    @Test fun readsOnlyRequestedChapterInSpineOrderWithRealParagraphs() {
        val source = makeBook(
            "<html><head><title>Metadata</title></head><body><p>Trời vừa sáng.</p><p>Cô mở cửa &amp; bước ra.<br />Mưa đã tạnh.</p></body></html>",
            "<html><body><h1>Chương 2 — Gặp lại</h1><p>Cậu nhận ra cô.</p></body></html>",
        )
        val parsed = EpubParser.parse(source, "test.epub", includeFirstContent = false)
        val id = parsed.getValue("id").jsonPrimitive.content
        assertFalse("firstContent" in parsed)
        assertEquals(JsonNull, EpubParser.chapter(id, 0)["prev_url"])
        val first = EpubParser.chapter(id, 0)
        assertEquals("Trời vừa sáng.\n\nCô mở cửa & bước ra.\nMưa đã tạnh.", first["content"]?.jsonPrimitive?.content)
        assertEquals("epub://$id/1", first["next_url"]?.jsonPrimitive?.content)
        val second = EpubParser.chapter(id, 1)
        assertEquals("Chương 2 — Gặp lại", second["title"]?.jsonPrimitive?.content)
        assertEquals("Cậu nhận ra cô.", second["content"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, second["next_url"])
        assertTrue(EpubParser.delete(id))
        assertFalse(File(directory, "epubs/$id.index-v1.json").exists())
    }

    @Test fun reconstructsIndexForPreviouslyImportedBookWithoutShiftingTitledCover() {
        val source = makeBook(
            "<html><body><p>Chương thứ nhất.</p></body></html>",
            "<html><body><p>Chương thứ hai.</p></body></html>",
            coverInSpine = true,
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val id = "epub-" + digest.take(12).joinToString("") { "%02x".format(it) }
        val saved = File(directory, "epubs/$id.epub")
        saved.parentFile?.mkdirs()
        source.copyTo(saved)
        assertEquals("3", EpubParser.info(id)["totalChapters"]?.jsonPrimitive?.content)
        assertEquals("Chương thứ hai.", EpubParser.chapter(id, 2)["content"]?.jsonPrimitive?.content)
        assertTrue(EpubParser.delete(id))
    }
}
