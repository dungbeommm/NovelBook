package vn.tramtruyen.reader.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCacheKeyTest {
    @Test
    fun keyIsStableAndCompact() {
        val identity = "story-42|https://example.com/cover.jpg|420"
        val key = fastCoverCacheKey(identity)
        assertEquals(key, fastCoverCacheKey(identity))
        assertTrue(key.startsWith("cover:"))
        assertTrue(key.length <= 22)
    }

    @Test
    fun storyAndDecodeSizeRemainPartOfIdentity() {
        assertNotEquals(
            fastCoverCacheKey("story-a|https://example.com/cover.jpg|128"),
            fastCoverCacheKey("story-b|https://example.com/cover.jpg|128"),
        )
        assertNotEquals(
            fastCoverCacheKey("story-a|https://example.com/cover.jpg|128"),
            fastCoverCacheKey("story-a|https://example.com/cover.jpg|900"),
        )
    }
}