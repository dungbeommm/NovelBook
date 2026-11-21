package vn.tramtruyen.reader.engine.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ban 1.006: lop phu JS/kiem-tra-san-sang theo site cua Thien
 * (assets/thien_site_js.json, rut gon tu ParserGuide cua Thien).
 * Cac test nay chay thuan tren JVM — khong can Config.appContext.
 */
class ThienSiteJsTest {
    private val assets: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
    }

    private val bundledText: String by lazy { assets.resolve("thien_site_js.json").readText() }

    @Test fun bundledAssetHasRealCoverage() {
        val parsed = ThienSiteJs.parse(bundledText)
        // 143 site co it nhat 1 trong 4 truong (dem that tu guides vi/cn/en/zh).
        assertEquals(143, parsed.entries.size)
        assertTrue(parsed.version.isNotBlank())
        assertEquals(65, parsed.entries.count { it.contentLoadJs != null })
        assertEquals(106, parsed.entries.count { it.dataLoadingChecker.isNotEmpty() })
        assertEquals(58, parsed.entries.count { it.detailFirstChapterJs != null })
        assertTrue(parsed.entries.all { it.domain.isNotBlank() })
        assertEquals(parsed.entries.size, parsed.entries.map { it.domain }.distinct().size)
    }

    @Test fun sangtacvietCarriesTheIframeTrick() {
        val parsed = ThienSiteJs.parse(bundledText)
        val entry = parsed.entries.single { it.domain == "sangtacviet.xyz" }
        val loadJs = entry.contentLoadJs.orEmpty()
        assertTrue(loadJs.contains("chapterfetcher"))
        assertTrue(loadJs.contains("XMLHttpRequest"))
        assertTrue(entry.dataReadyChecker.contains("Nhấp vào để tải chương"))
        assertTrue(entry.dataReadyChecker.contains("Đang tải nội dung chương"))
        assertEquals("click:#readnowbtn", entry.detailFirstChapterJs)
        assertTrue(entry.matches("sangtacviet.xyz"))
        assertTrue(entry.matches("www.sangtacviet.xyz"))
        assertTrue(entry.matches("sangtacviet"))
        assertFalse(entry.matches("notsangtacviet.xyz"))
        assertFalse(entry.matches("sangtacviet.xyz.evil.example"))
    }

    @Test fun mergeIntoFillsBlanksButNeverOverwritesParserJson() {
        val parsed = ThienSiteJs.parse(bundledText)
        val stv = parsed.entries.single { it.domain == "sangtacviet.xyz" }

        // Site da co JS rieng trong parser.json: giu nguyen.
        val own = SiteConfig(domain = "sangtacviet.xyz", contentLoadJs = "keep-me")
        val kept = ThienSiteJs.mergeInto(own, stv)
        assertEquals("keep-me", kept.contentLoadJs)
        // Truong con thieu thi dien them tu Thien.
        assertEquals(stv.dataReadyChecker, kept.dataReadyChecker)

        // Site trong: dien day du ca 4 truong.
        val blank = SiteConfig(domain = "sangtacviet.xyz")
        val filled = ThienSiteJs.mergeInto(blank, stv)
        assertEquals(stv.contentLoadJs, filled.contentLoadJs)
        assertEquals(stv.dataReadyChecker, filled.dataReadyChecker)
        assertEquals(stv.dataLoadingChecker, filled.dataLoadingChecker)
        assertEquals(stv.detailFirstChapterJs, filled.detailFirstChapterJs)
    }

    @Test fun bundledDataHasNoEntryForUnknownHost() {
        // overlayConfigFor() can nut Config.appContext nen khong test truc tiep
        // tren JVM thuan; o day chi khang dinh du lieu dong goi khong co muc la.
        val parsed = ThienSiteJs.parse(bundledText)
        assertNull(parsed.entries.singleOrNull { it.domain == "example.com" })
    }

    @Test fun wattpadGainsContentLoadJsOverParserJson() {
        val parsed = ThienSiteJs.parse(bundledText)
        val entry = parsed.entries.single { it.domain == "www.wattpad.com" }
        assertNotNull(entry.contentLoadJs)
        // parser.json cua Wattpad truoc 1.006 khong co content_load_js.
        val blank = SiteConfig(domain = "www.wattpad.com")
        assertEquals(entry.contentLoadJs, ThienSiteJs.mergeInto(blank, entry).contentLoadJs)
    }

    @Test fun parseIgnoresMalformedEntries() {
        val parsed = ThienSiteJs.parse(
            """{"version":"2026-11-20T00:00:00Z","sites":[
              {"domain":"a.com","content_load_js":"x();"},
              {"domain":"","content_load_js":"y();"},
              {"nope":true}
            ]}""",
        )
        assertEquals(1, parsed.entries.size)
        assertEquals("a.com", parsed.entries.single().domain)
        assertEquals("x();", parsed.entries.single().contentLoadJs)
    }
}
