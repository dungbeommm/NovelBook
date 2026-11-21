package vn.tramtruyen.reader.engine.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HakoParserAssetTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
    }

    @Test fun catalogAndManifestContainHako() {
        val catalog = json.parseToJsonElement(
            assets.resolve("parser_v4/catalog.json").readText(),
        ).jsonObject
        val sources = catalog.getValue("sources").jsonArray
        val hako = sources.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "ln.hako.vn" }

        assertEquals(sources.size, catalog.getValue("count").jsonPrimitive.content.toInt())
        assertTrue(hako.getValue("domains").jsonArray.any { it.jsonPrimitive.content == "ln.hako.vn" })

        val manifest = json.parseToJsonElement(
            assets.resolve("parser_v4/sources/ln.hako.vn/manifest.json").readText(),
        ).jsonObject
        assertEquals("https://ln.hako.vn", manifest.getValue("homepage_url").jsonPrimitive.content)
        assertTrue(manifest.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "search" })
    }

    @Test fun nativeSearchConfigUsesVerifiedHakoSelectors() {
        val configs = json.parseToJsonElement(assets.resolve("parser.json").readText()).jsonArray
        val hako = configs.map { it.jsonObject }
            .single { it.getValue("domain").jsonPrimitive.content == "ln.hako.vn" }
        val search = hako.getValue("search").jsonObject

        assertEquals("https://ln.hako.vn/tim-kiem?keywords=%s", search.getValue("url").jsonPrimitive.content)
        assertEquals(".thumb-item-flow", search.getValue("item").jsonObject.getValue("selector").jsonPrimitive.content)
        assertEquals(".series-title a", search.getValue("link").jsonObject.getValue("selector").jsonPrimitive.content)
        assertEquals("#chapter-content", hako.getValue("content").jsonObject.getValue("selector").jsonPrimitive.content)
        // Hako's chapter body is already present in server HTML. HTTP-first is
        // more reliable across vendor WebView, IPv6 and mobile-carrier variants.
        assertTrue(!hako.getValue("use_webview").jsonPrimitive.content.toBoolean())
    }
}