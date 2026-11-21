package vn.tramtruyen.reader.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.io.File

class StoreStoryLifecycleTest {
    private lateinit var dir: File
    @Before fun setup() { dir = Files.createTempDirectory("story-lifecycle").toFile(); Config.init(dir) }
    @After fun teardown() { dir.deleteRecursively() }
    private fun book(id: String, host: String = "a.com", path: String = "old", title: String = "Truyện A") = buildJsonObject {
        put("id", id); put("title", title); put("source", "Label")
        put("storyUrl", "https://$host/$path"); put("chapterUrl", "https://$host/$path/chapter-1")
    }
    private fun chapter(url: String, number: Int = 1) = buildJsonObject {
        put("chapterId", url); put("chapterUrl", url); put("chapterIndex", number)
    }
    @Test fun latestLinkReplacesSameTitleOnSameWebsiteOnly() {
        Store.upsertLibrary(book("old")); Store.upsertLibrary(book("other", host = "b.com"))
        Store.upsertLibrary(book("new", path = "new", title = "  TRUYỆN   A  "))
        val rows = Store.getLibrary()
        assertEquals(2, rows.size)
        assertEquals("https://a.com/new/chapter-1", rows.first()["chapterUrl"]?.jsonPrimitive?.content)
        assertTrue(rows.any { it["id"]?.jsonPrimitive?.content == "other" })
    }
    @Test fun sameChapterNumberReplacesOldChapterLink() {
        Store.upsertChapterHistory("s", chapter("https://a.com/old/chapter-1"))
        Store.upsertChapterHistory("s", chapter("https://a.com/new/chapter-1"))
        Store.upsertChapterHistory("s", chapter("https://a.com/new/chapter-2", 2))
        assertEquals(2, Store.getChapterHistory("s").size)
        assertFalse(Store.getChapterHistory("s").any { it["chapterUrl"]?.jsonPrimitive?.content?.contains("/old/") == true })
    }
    @Test fun deletionScrubsAliasesProgressChaptersAndRecoveryFilesButKeepsOtherStory() {
        val old = book("old"); val new = book("new", path = "new")
        Store.upsertLibrary(old); Store.upsertHistory(old)
        Store.upsertProgress("old", chapter("https://a.com/old/chapter-1"))
        Store.upsertChapterHistory("old", chapter("https://a.com/old/chapter-1"))
        Store.upsertLibrary(new); Store.upsertHistory(new)
        Store.updateLibraryProgress(new, buildJsonObject { put("currentChapter", 2) })
        val other = book("other", host = "b.com")
        Store.upsertLibrary(other); Store.upsertHistory(other)
        Store.upsertProgress("new", chapter("https://a.com/new/chapter-1"))
        Store.upsertChapterHistory("new", chapter("https://a.com/new/chapter-1"))
        dir.resolve("library.json.v1.bak").writeText(old.toString())
        dir.resolve("history.json.corrupt-123").writeText(old.toString())
        val aliases = Store.deleteStory(new)
        assertTrue("old" in aliases)
        assertEquals(listOf("other"), Store.getLibrary().map { it["id"]?.jsonPrimitive?.content })
        assertEquals(listOf("other"), Store.getHistory().map { it["id"]?.jsonPrimitive?.content })
        assertTrue(Store.getAllProgress().isEmpty())
        assertTrue(Store.getChapterHistory("old").isEmpty())
        assertTrue(Store.getChapterHistory("new").isEmpty())
        assertFalse(dir.listFiles().orEmpty().any { it.name.contains(".bak") || it.name.contains(".corrupt-") })
        assertFalse(Store.exportBackup().contains("a.com"))
    }
    @Test fun queuedWriteCannotResurrectDeletedStoryButFreshImportIsAllowed() {
        val story = book("s"); Store.upsertLibrary(story)
        val epoch = Store.writeGeneration()
        Store.deleteStory(story)
        Store.withWriteGeneration(epoch) { Store.upsertLibrary(story); Store.upsertHistory(story) }
        assertTrue(Store.getLibrary().isEmpty()); assertTrue(Store.getHistory().isEmpty())
        Store.withWriteGeneration(Store.writeGeneration()) { Store.upsertLibrary(story) }
        assertEquals(1, Store.getLibrary().size)
    }
}
