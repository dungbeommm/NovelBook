package vn.tramtruyen.reader.engine.sources

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiem chung engine SuperExtractor (port co che SuperCrawler cua Aufi). */
class SuperExtractorTest {

    @Test
    fun dropsShopeeUnlockTrapLines() {
        val prose = (1..12).joinToString("\n") {
            "Đoạn văn truyện số $it, nhân vật bước vào phòng và nhìn quanh một lượt thật kỹ."
        }
        val dirty = prose + "\nNHẤN vào LIÊN KẾT hoặc ẢNH bên dưới\nTruyện hay tại vt.tiktok.com/abc"
        val cleaned = SuperExtractor.cleanText(dirty)
        assertTrue(cleaned.contains("Đoạn văn truyện số 12"))
        assertFalse(cleaned.contains("NHẤN vào LIÊN KẾT"))
        assertFalse(cleaned.contains("vt.tiktok.com"))
    }

    @Test
    fun cutsTailJunkRunButKeepsProse() {
        val prose = (1..20).joinToString("\n") {
            "Dòng nội dung truyện thứ $it có diễn biến và kết thúc bằng dấu chấm."
        }
        val junkTail = listOf("Quảng cáo", "Tải app ngay", "Xem thêm", "Chia sẻ", "Bình luận").joinToString("\n")
        val cleaned = SuperExtractor.cleanText(prose + "\n" + junkTail)
        assertTrue(cleaned.contains("Dòng nội dung truyện thứ 20"))
        assertFalse(cleaned.contains("Quảng cáo"))
        assertFalse(cleaned.contains("Bình luận"))
    }

    @Test
    fun detectsCloudflareAndWalls() {
        assertEquals("bot_check", SuperExtractor.junkKind("<html><script>window._cf_chl_opt={}</script></html>"))
        assertEquals("bot_check", SuperExtractor.junkKind("<html><body>Just a moment... Checking your browser</body></html>"))
        assertEquals("site_login", SuperExtractor.junkKind("<html><body>Vui lòng đăng nhập để đọc tiếp chương này</body></html>"))
        assertNull(SuperExtractor.junkKind("<html><body>" + "Nội dung truyện bình thường. ".repeat(200) + "</body></html>"))
    }

    @Test
    fun keywordCascadeFindsNextChapter() {
        val doc = Jsoup.parse(
            """
            <a href="/the-loai/tien-hiep">Tiên hiệp</a>
            <a href="/truyen/chuong-1">Chương 1</a>
            <a href="/truyen/chuong-2">Chương sau</a>
            """.trimIndent(),
            "https://example.com/truyen/chuong-1",
        )
        assertEquals(
            "https://example.com/truyen/chuong-2",
            SuperExtractor.findNextByCascade(doc, "https://example.com/truyen/chuong-1"),
        )
    }

    @Test
    fun selectorChainEscalatesToLongerProseBlock() {
        val shortText = "Đoạn mở đầu ngắn ngủi này chỉ là phần giới thiệu tượng trưng cho có, " +
            "không phải nội dung chương thật sự mà người đọc đang cần tìm ở đây."
        val longText = (1..30).joinToString(" ") {
            "Câu văn nội dung chương thứ $it diễn biến tiếp nối nhau rất rõ ràng."
        }
        val doc = Jsoup.parse(
            """
            <div id="chapter-c">$shortText</div>
            <div class="chapter-content"><p>$longText</p></div>
            """.trimIndent(),
            "https://example.com",
        )
        val el = SuperExtractor.selectContentElement(doc)
        assertTrue(el != null && el.hasClass("chapter-content"))
    }
}
