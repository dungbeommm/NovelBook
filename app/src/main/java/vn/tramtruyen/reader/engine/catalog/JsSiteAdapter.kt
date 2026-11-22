package vn.tramtruyen.reader.engine.catalog

import vn.tramtruyen.reader.engine.Cleaner
import vn.tramtruyen.reader.engine.sources.BaseSiteAdapter
import vn.tramtruyen.reader.engine.sources.ChapterData
import vn.tramtruyen.reader.engine.sources.ChapterLink
import vn.tramtruyen.reader.engine.sources.GenericExtractor
import org.jsoup.Jsoup
import vn.tramtruyen.reader.engine.sources.StoryMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapter engine v4 — dung ma nguon JS tu kho parser source repository.
 *
 * Hieu legacy extension/Android fields moi:
 *  - story: name/cover/author/description + coverUrl/firstChapterUrl
 *  - chapter: content/contentHtml/images/imageOnly/nextUrl/previousUrl
 *  - toc/search: chapters/items + link/name/cover
 *
 * Neu script loi hoac tra ve rong, quay lai adapter cu (parser.json).
 */
class JsSiteAdapter(
    private val source: ParserSourceStore.Source,
    private val fallback: BaseSiteAdapter? = null,
) : BaseSiteAdapter() {

    override val supportedDomains: List<String> = source.domains

    override suspend fun getChapter(html: String, url: String): ChapterData {
        val r = run("parseChapter", html, url)
            ?: return fallback?.getChapter(html, url) ?: ChapterData("", "", url)

        val textContent = pick(r, "content", "text", "body", "chapterContent")
        val htmlContent = pick(r, "contentHtml", "html")
        val images = imageUrls(r)
        val render = Jsoup.parse(html, url)
        val prose = render.selectFirst(".tdai-content, section[itemprop=text], .prose-novel")
        val hasProse = prose?.text()?.any(Char::isLetterOrDigit) == true
        val imageOnly = !hasProse && (bool(r, "imageOnly") || textContent.isBlank() && images.isNotEmpty())
        val chapterTitle = pick(r, "title", "chapterTitle", "chapter_title", "name")
        val storyTitle = GenericExtractor.cleanStoryTitle(
            pick(r, "storyTitle", "story", "novelTitle", "story_title", "bookName"),
            chapterTitle,
        ).ifBlank { GenericExtractor.guessStoryTitle(render) }
        val content = buildChapterContent(textContent, prose?.outerHtml() ?: htmlContent, images, imageOnly)
        val data = ChapterData(
            title = chapterTitle,
            content = Cleaner.stripLeadingTitles(content, listOf(chapterTitle, storyTitle)),
            chapterUrl = pick(r, "url", "chapterUrl").ifBlank { url },
            nextUrl = pick(r, "nextUrl", "next", "next_url").takeIf { it.isNotBlank() },
            prevUrl = pick(r, "previousUrl", "prevUrl", "prev", "prev_url").takeIf { it.isNotBlank() },
            storyTitle = storyTitle,
        )
        if (data.content.isBlank() && fallback != null) {
            return fallback.getChapter(html, url)
        }
        return data
    }

    override suspend fun getStoryMeta(html: String, url: String): StoryMeta {
        val r = run("parseStory", html, url)
            ?: return fallback?.getStoryMeta(html, url) ?: StoryMeta()
        val meta = StoryMeta(
            title = pick(r, "name", "title", "storyTitle"),
            author = pick(r, "author", "authorName"),
            coverUrl = pick(r, "cover", "coverUrl", "image", "thumbnail"),
            description = pick(r, "descriptionText", "description", "desc", "summary", "intro"),
        )
        if (meta.title.isBlank() && meta.coverUrl.isBlank() && fallback != null) {
            return fallback.getStoryMeta(html, url)
        }
        return meta
    }

    override suspend fun getChapterList(html: String, url: String): List<ChapterLink> {
        val r = run("parseChapterList", html, url)
            ?: return fallback?.getChapterList(html, url) ?: emptyList()
        val arr = firstArray(r, "chapters", "items", "list", "data")
            ?: return fallback?.getChapterList(html, url) ?: emptyList()
        val out = ArrayList<ChapterLink>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val link = pick(o, "url", "href", "link")
            if (link.isBlank()) continue
            out += ChapterLink(
                title = pick(o, "title", "name", "text").ifBlank { "Chương ${i + 1}" },
                url = link,
            )
        }
        return if (out.isEmpty() && fallback != null) fallback.getChapterList(html, url) else out
    }

    override fun getTocUrl(chapterUrl: String): String? = fallback?.getTocUrl(chapterUrl)

    override suspend fun getFirstChapterUrl(html: String, url: String): String? {
        val story = run("parseStory", html, url)
        pick(story, "firstChapterUrl", "first_chapter", "firstChapter")
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        getChapterList(html, url).firstOrNull()?.url?.let { return it }
        return fallback?.getFirstChapterUrl(html, url)
    }

    // ---- ha tang ----

    private suspend fun run(action: String, html: String, url: String): JSONObject? {
        if (!source.can(actionCapability(action))) return null
        val payload = JSONObject()
            .put("html", html)
            .put("url", url)
            .put("baseUrl", url)
        return JsSourceEngine.execute(source, action, payload)
    }

    private fun actionCapability(action: String): String = when (action) {
        "parseChapter" -> "chapter"
        "parseChapterList" -> "chapter_list"
        "parseStory", "parseDetail" -> "story"
        "parseSearch" -> "search"
        "findCover" -> "cover"
        else -> action
    }

    private fun buildChapterContent(
        textContent: String,
        htmlContent: String,
        images: List<String>,
        imageOnly: Boolean,
    ): String {
        val cleanedText = htmlContent.takeIf(String::isNotBlank)
            ?.let { Cleaner.cleanChapterContent(it) }?.takeIf(String::isNotBlank)
            ?: if (Regex("(?i)<(?:p|div|br|span|section|article)\\b").containsMatchIn(textContent)) {
                Cleaner.cleanChapterContent(textContent)
            } else Cleaner.cleanChapterText(textContent)
        if (images.isEmpty()) return cleanedText
        val imageBlock = images.joinToString("\n") { "[ảnh] $it" }
        return if (imageOnly || cleanedText.isBlank()) {
            imageBlock
        } else {
            cleanedText.trimEnd() + "\n\n" + imageBlock
        }
    }

    private fun imageUrls(o: JSONObject?): List<String> {
        if (o == null) return emptyList()
        val data = o.optJSONObject("data") ?: o
        val arr = data.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val url = when (item) {
                is JSONObject -> item.optString("url").ifBlank {
                    item.optString("src").ifBlank { item.optString("href") }
                }
                is String -> item
                else -> ""
            }
            if (url.isNotBlank() && url != "null") out += url
        }
        return out
    }

    private fun pick(o: JSONObject?, vararg keys: String): String {
        if (o == null) return ""
        val data = o.optJSONObject("data") ?: o
        for (k in keys) {
            val v = data.optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun bool(o: JSONObject?, key: String): Boolean {
        if (o == null) return false
        val data = o.optJSONObject("data") ?: o
        return data.optBoolean(key, false)
    }

    private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
        val data = o.optJSONObject("data") ?: o
        for (k in keys) data.optJSONArray(k)?.let { return it }
        return null
    }
}
