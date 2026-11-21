package vn.tramtruyen.reader.engine.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import vn.tramtruyen.reader.engine.ApiError

class SangTacVietChapterTest {
    private val url = "https://sangtacviet.xyz/truyen/jjwxc/1/1234/7/"

    @Test fun extractsMatchingChapterAndNextLink() {
        val (chapter, story) = SangTacVietChapter.parse("""
            <meta property="og:image" content="/covers/1234.png">
            <div id="booknameholder">Tên truyện</div>
            <h1 id="bookchapnameholder">Đêm mưa</h1>
            <div class="contentbox" cid="6">Văn bản chương khác.</div>
            <div class="contentbox" cid="7"><p>Đêm mưa</p><p>Dòng đầu.</p><p>Dòng sau.</p></div>
            <a id="navnexttop" href="/truyen/jjwxc/1/1234/8/"></a>
            <a id="navprevtop" href="/truyen/jjwxc/1/5678/6/"></a>
        """.trimIndent(), url)
        assertEquals("Tên truyện", story.title)
        assertEquals("https://sangtacviet.xyz/covers/1234.png", story.coverUrl)
        assertEquals("Đêm mưa", chapter.title)
        assertEquals("Dòng đầu.\nDòng sau.", chapter.content)
        assertEquals("https://sangtacviet.xyz/truyen/jjwxc/1/1234/8/", chapter.nextUrl)
        assertNull(chapter.prevUrl)
    }

    @Test fun rejectsDifferentChapterAndLookalikeHost() {
        assertThrows(ApiError::class.java) {
            SangTacVietChapter.parse(
                """<h1 id="bookchapnameholder">Đêm mưa</h1><div class="contentbox" cid="6">Văn bản cũ.</div>""",
                url,
            )
        }
        assertFalse(SangTacVietChapter.matches(url.replace("sangtacviet.xyz", "sangtacviet.xyz.example")))
    }
}
