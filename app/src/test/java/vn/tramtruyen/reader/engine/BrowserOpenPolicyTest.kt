package vn.tramtruyen.reader.engine

import org.junit.Assert.*
import org.junit.Test

class BrowserOpenPolicyTest {
    @Test fun chapterBeforeStoryBeforeHome() {
        assertEquals("https://a.com/book/chapter-3", BrowserOpenPolicy.resolve(chapterUrl = "https://a.com/book/chapter-3", storyUrl = "https://a.com/book"))
        assertEquals("https://a.com/book", BrowserOpenPolicy.resolve(chapterUrl = "epub://book/3", storyUrl = "https://a.com/book"))
        assertEquals(BrowserOpenPolicy.HOME, BrowserOpenPolicy.resolve(chapterUrl = "epub://book/3"))
    }
    @Test fun addSourceExplicitlyOpensHome() {
        assertEquals(BrowserOpenPolicy.HOME, BrowserOpenPolicy.resolve(explicit = BrowserOpenPolicy.HOME, chapterUrl = "https://a.com/book/chapter-3"))
    }
    @Test fun explicitWebLinkWinsAndInvalidLinksAreIgnored() {
        assertEquals("https://a.com/new", BrowserOpenPolicy.resolve(explicit = "https://a.com/new", chapterUrl = "https://a.com/old"))
        assertEquals(BrowserOpenPolicy.HOME, BrowserOpenPolicy.resolve(explicit = "javascript:alert(1)", chapterUrl = ""))
    }
}
