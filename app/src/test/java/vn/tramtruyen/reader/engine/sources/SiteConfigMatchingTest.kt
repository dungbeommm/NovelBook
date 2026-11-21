package vn.tramtruyen.reader.engine.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteConfigMatchingTest {
    @Test
    fun matchesExactDomainSubdomainsAndWholeAliasLabels() {
        val config = SiteConfig(
            domain = "aatruyen.net",
            keys = listOf("aatruyen", "www.aatruyen.net"),
        )

        assertTrue(config.matches("aatruyen.net"))
        assertTrue(config.matches("www.aatruyen.net"))
        assertTrue(config.matches("m.aatruyen.net"))
        assertTrue(config.matches("aatruyen"))
    }

    @Test
    fun rejectsHostnamesThatOnlyContainTheDomainText() {
        val config = SiteConfig(
            domain = "aatruyen.net",
            keys = listOf("aatruyen"),
        )

        assertFalse(config.matches("evilaatruyen.net"))
        assertFalse(config.matches("aatruyen.net.example.org"))
        assertFalse(config.matches("unrelated.example.org"))
    }
}