package vn.tramtruyen.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test fun acceptsHttpsWithARealHost() {
        assertTrue(UrlPolicy.isAllowed("https://example.org/story/1"))
        assertEquals("https://example.org/story/1", UrlPolicy.normalizeHttps(" https://example.org/story/1 "))
    }

    @Test fun rejectsCleartextCredentialsAndMalformedUrls() {
        assertFalse(UrlPolicy.isAllowed("http://example.org/story"))
        assertNull(UrlPolicy.normalizeHttps("https://user:pass@example.org/story"))
        assertFalse(UrlPolicy.isAllowed("https:///missing-host"))
        assertFalse(UrlPolicy.isAllowed("not a url"))
    }
}