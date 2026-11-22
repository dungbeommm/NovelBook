package vn.tramtruyen.reader.engine.sources

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericExtractorTest {
    @Test
    fun selectsTheBestMatchingContentInsteadOfTheFirstMatch() {
        val longText = (1..20).joinToString(" ") {
            "Đây là đoạn nội dung truyện thứ $it, có nhân vật và diễn biến rõ ràng."
        }
        val doc = Jsoup.parse(
            """
            <div id="content">Đoạn giới thiệu ngắn.</div>
            <article class="chapter-content"><p>$longText</p></article>
            """.trimIndent(),
        )

        val content = GenericExtractor.extractContent(doc)
        assertTrue(content.contains("đoạn nội dung truyện thứ 20"))
        assertFalse(content.contains("Đoạn giới thiệu ngắn"))
    }

    @Test
    fun preservesInlineProseAndGetsBookNameFromBreadcrumb() {
        val opener = "Sở Trường Ca Đồng<i> Tha</i>u Chiến Thể mang theo uy năng đáng sợ."
        val chapter = """
            <main>
              <div class="breadcrumb"><a href="/">Trang chủ</a>
                <a href="/truyen/thai-co-than-ton">Thái Cổ Thần Tôn Dịch AIConvert</a>
                <a href="/truyen/thai-co-than-ton/chuong-8">Chương 8</a>
              </div>
              <h1 class="chapter-title">Chương 8</h1>
              <section itemprop="text"><p>18:11 13/06/2026|$opener</p>
                <p>Chương trước còn nhắc đến chuyện này.</p>
              </section>
            </main>
        """.trimIndent()
        val doc = Jsoup.parse(chapter, "https://example.com/truyen/thai-co-than-ton/chuong-8")
        assertEquals("Thái Cổ Thần Tôn", GenericExtractor.guessStoryTitle(doc))
        assertEquals("Chương 8", GenericExtractor.guessTitle(doc))
        assertEquals(
            "Sở Trường Ca Đồng Thau Chiến Thể mang theo uy năng đáng sợ.\nChương trước còn nhắc đến chuyện này.",
            GenericExtractor.extractContent(doc),
        )
    }

    @Test
    fun rejectsStaleChapterHeadingDuringNavigation() {
        val url = "https://example.com/story/chuong-9"
        assertFalse(GenericExtractor.looksLikeChapterPage(
            "<section itemprop='text'>Đoạn truyện cũ.</section>", url, ChapterData("Chương 8", "Đoạn truyện cũ.", url),
        ))
    }

    @Test
    fun keepsSourceParserWhenCandidatesAreSimilar() {
        val source = "Nội dung chương sạch. ".repeat(30)
        val generic = "Nội dung chương sạch. ".repeat(31)
        assertEquals(source.trim(), GenericExtractor.chooseBetter(source, generic))
    }

    @Test
    fun recognizesChineseChapterLinksByVisibleText() {
        val doc = Jsoup.parse(
            """
            <a href="/book/10001">第一章 Khởi đầu</a>
            <a href="/book/10002">第二章 Gặp gỡ</a>
            <a href="/about">Giới thiệu</a>
            """.trimIndent(),
            "https://example.com",
        )

        val links = GenericExtractor.guessChapterLinks(doc)
        assertEquals(2, links.size)
    }
}