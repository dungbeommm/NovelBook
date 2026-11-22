package vn.tramtruyen.reader.engine.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParserSourceStoreTest {
    private fun source(domain: String) = ParserSourceStore.Source(
        id = domain,
        name = domain,
        domains = listOf(domain),
        useWebview = false,
        useHtmlParser = true,
        mayNeedVpn = false,
        capabilities = emptyList(),
        version = 4,
        engine = 4,
        homepage = "",
        logo = "",
        scriptFile = File("unused.js"),
    )

    @Test
    fun matchesExactAndSubdomainsOnly() {
        val source = source("aatruyen.net")

        assertTrue(source.matches("aatruyen.net"))
        assertTrue(source.matches("www.aatruyen.net"))
        assertTrue(source.matches("m.www.aatruyen.net"))
    }

    @Test
    fun rejectsDomainsThatOnlyContainTheSourceName() {
        val source = source("aatruyen.net")

        assertFalse(source.matches("evilaatruyen.net"))
        assertFalse(source.matches("aatruyen.net.example.org"))
        assertFalse(source.matches("unrelated.example.org"))
    }
}