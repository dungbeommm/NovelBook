package vn.tramtruyen.reader.engine.sources

import org.jsoup.Jsoup
import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.Cleaner
import java.net.URI

internal object SangTacVietChapter {
    val origins = setOf("https://sangtacviet.xyz", "https://sangtacviet.app", "https://sangtacviet.com",
        "https://sangtacviet.vip", "https://www.sangtacviet.xyz", "https://www.sangtacviet.app",
        "https://www.sangtacviet.com", "https://www.sangtacviet.vip")
    private val path = Regex("/truyen/([\\w-]+)/\\d+/([\\w-]+)/([\\w-]+)/?")

    private fun siteUri(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            "${uri.scheme}://${uri.host?.lowercase()}" !in origins) return null
        return uri
    }

    fun supportsSite(url: String): Boolean = siteUri(url) != null

    private fun chapterParts(url: String): List<String>? {
        val uri = siteUri(url) ?: return null
        return path.matchEntire(uri.rawPath.orEmpty())?.groupValues?.drop(1)?.takeUnless { it.last() == "0" }
    }

    fun matches(url: String): Boolean = chapterParts(url) != null

    fun parse(html: String, url: String): Pair<ChapterData, StoryMeta> {
        val parts = requireNotNull(chapterParts(url))
        val doc = Jsoup.parse(html, url)
        val root = doc.select(".contentbox[cid]").firstOrNull { it.attr("cid") == parts.last() }
        val title = doc.selectFirst("#bookchapnameholder")?.text().orEmpty()
        val story = doc.selectFirst("#booknameholder")?.text().orEmpty()
        val content = root?.let { Cleaner.cleanChapterContent(it.outerHtml()) }.orEmpty()
        if (title.none(Char::isLetter) || content.none(Char::isLetter)) {
            throw ApiError(422, "Hãy mở chương Sáng Tác Việt và đợi trang hiển thị văn bản.")
        }
        fun link(id: String): String? {
            val href = doc.getElementById(id)?.absUrl("href") ?: return null
            val target = chapterParts(href) ?: return null
            return href.takeIf { target.take(2) == parts.take(2) && target.last() != parts.last() }
        }
        return ChapterData(
            title = title,
            content = Cleaner.stripLeadingTitles(content, listOf(title, story)),
            chapterUrl = url,
            nextUrl = link("navnexttop"),
            prevUrl = link("navprevtop"),
            storyTitle = story,
        ) to StoryMeta(title = story, coverUrl = doc.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty())
    }
}
