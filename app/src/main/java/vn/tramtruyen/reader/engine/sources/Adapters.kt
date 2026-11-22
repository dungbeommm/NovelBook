package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.TextPipeline
import vn.tramtruyen.reader.engine.HttpClient
import vn.tramtruyen.reader.engine.Cleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Du lieu 1 chuong — port cua base_adapter.ChapterData. */
data class ChapterData(
    val title: String,
    val content: String,
    val chapterUrl: String,
    val nextUrl: String? = null,
    val prevUrl: String? = null,
    val storyTitle: String = ""
)

/** Metadata truyen — port cua base_adapter.StoryMeta. */
data class StoryMeta(
    val title: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val description: String = ""
)

/** 1 muc trong danh sach chuong (tu trang muc luc). */
data class ChapterLink(val title: String, val url: String)

internal fun firstMatch(doc: Document, selector: String): Element? =
    try { doc.selectFirst(selector) } catch (e: Exception) { null }

/**
 * Adapter co so — moi site chi can implement getChapter + getStoryMeta.
 * Nhan HTML da tai san (viec fetch do caller lo), giong ban Python.
 */
abstract class BaseSiteAdapter {
    abstract val supportedDomains: List<String>

    /**
     * Khop theo NHAN MIEN (giong SiteConfig.matches): host phai bang han hoac la
     * subdomain cua domain duoc ho tro. Ban cu dung contains nen cac host nhu
     * "notmetruyenchu.com" hay "metruyenchu.com.evil.com" cung an adapter nay.
     */
    fun canHandle(domain: String): Boolean {
        val h = domain.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        return supportedDomains.any { raw ->
            val candidate = raw.trim().trim('.').lowercase()
            candidate.isNotEmpty() && (h == candidate || h.endsWith(".$candidate"))
        }
    }

    abstract suspend fun getChapter(html: String, url: String): ChapterData
    abstract suspend fun getStoryMeta(html: String, url: String): StoryMeta

    /** Danh sach chuong (MUC LUC) trich tu trang gioi thieu / trang muc luc. */
    open suspend fun getChapterList(html: String, url: String): List<ChapterLink> = emptyList()
    /** Suy ra URL trang muc luc/gioi thieu tu 1 URL chuong (de tai roi lay danh sach chuong). */
    open fun getTocUrl(chapterUrl: String): String? = null
    /** Tu trang gioi thieu -> URL CHUONG DAU (khi nguoi dung dan link trang truyen, khong phai chuong). */
    open suspend fun getFirstChapterUrl(html: String, url: String): String? = null
}

private val LSTRIP_TITLE_CHARS = charArrayOf(' ', '-', ':', '\u2013', '\u2014', '.', '\u2022', '\u00b7', '|', '\u00bb', '\u00ab')
private val SPLIT_SEP = Regex("\\s*[-:\u2013\u2014|]\\s*")
private val CHUONG_RE = Regex("^chuong-\\d+")

private fun stripStoryFromTitle(title: String, storyTitle: String): String {
    if (title.isBlank() || storyTitle.isBlank()) return title
    val t = title.trim()
    val s = storyTitle.trim()
    if (t.lowercase().startsWith(s.lowercase())) {
        val rest = t.substring(s.length).trimStart(*LSTRIP_TITLE_CHARS)
        if (rest.isNotEmpty()) return rest.trim()
    }
    val parts = SPLIT_SEP.split(t, 2)
    if (parts.size == 2) {
        val a = parts[0].trim(); val b = parts[1].trim()
        if (a.lowercase() == s.lowercase()) return b
        if (b.lowercase() == s.lowercase()) return a
    }
    return t
}

private fun resolveNext(doc: Document, primarySelector: String, extraHrefHint: String? = null): String? {
    var nextEl: Element? = firstMatch(doc, primarySelector)
    if (nextEl == null) {
        // Fallback theo TEXT: chi chap nhan text TRON VEN la nhan "chuong sau".
        // Ban cu dung contains("next") nen bat ca quang cao / link rac co chua
        // tu do o bat ky dau.
        val nextTexts = setOf("next", "ch\u01b0\u01a1ng sau", "ch\u01b0\u01a1ng ti\u1ebfp", "ti\u1ebfp theo", "\u203a", "\u00bb", ">")
        for (a in doc.select("a")) {
            val text = a.text().trim().lowercase()
            val href = a.attr("href").lowercase()
            if (text in nextTexts || (extraHrefHint != null && href.contains(extraHrefHint))) {
                nextEl = a; break
            }
        }
    }
    if (nextEl != null) {
        val href = nextEl.attr("href")
        if (href.isNotBlank() && href != "#" && !href.contains("javascript:")) {
            val abs = nextEl.absUrl("href")
            // Nut "chuong sau" o chuong cuoi thuong tu tro ve chinh trang hien
            // tai: tra ve no se lam prefetch/doc lien tuc lap vo han 1 chuong.
            if (abs.isNotBlank() && !samePageUrl(abs, doc.location())) return abs
        }
    }
    return null
}

/** Hai URL co la MOT trang khong (bo fragment + dau / cuoi). */
private fun samePageUrl(a: String, b: String): Boolean {
    fun norm(u: String): String = u.substringBefore('#').trimEnd('/').lowercase()
    val na = norm(a)
    return na.isNotEmpty() && na == norm(b)
}

/**
 * PHAN TRANG MUC LUC dung chung cho cac adapter.
 *
 * getChapterList chi nhan HTML cua TRANG DAU (caller tai san bang PageFetcher),
 * nen o day tu di tiep cac trang muc luc ke sau khi trang hien tai con link
 * "trang tiep", gop tat ca lai va khu trung theo URL. Tran 30 trang, dung som
 * khi mot trang khong them chuong nao moi (tranh lap qua widget/link rac co
 * cung mau phan trang).
 */
internal object TocPager {
    private const val MAX_TOC_PAGES = 30
    private val PAGE_TOKEN = Regex("(?:trang-|page[-=/]|/page/|[?&]p(?:age)?=)(\\d+)", RegexOption.IGNORE_CASE)
    private val QUERY_PAGE = Regex("[?&](?:p|page)=\\d+", RegexOption.IGNORE_CASE)
    private val PATH_PAGE = Regex("/(?:trang-|page[-/])\\d+", RegexOption.IGNORE_CASE)
    private val BARE_PAGE = Regex("(?:trang-|page=)\\d+", RegexOption.IGNORE_CASE)
    private val NEXT_TEXTS = setOf("trang sau", "trang ti\u1ebfp", "next", "\u203a", "\u00bb", ">", "sau")

    suspend fun collectAll(
        firstHtml: String,
        firstUrl: String,
        parsePage: (html: String, url: String) -> List<ChapterLink>,
    ): List<ChapterLink> {
        val merged = LinkedHashMap<String, ChapterLink>()
        var html = firstHtml
        var url = firstUrl
        var pages = 0
        val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
        try {
            while (true) {
                pages++
                val doc = Jsoup.parse(html, url)
                var added = 0
                for (ch in parsePage(html, url)) {
                    val key = ch.url.trimEnd('/')
                    if (ch.url.isNotBlank() && key !in merged) { merged[key] = ch; added++ }
                }
                if (pages >= MAX_TOC_PAGES) break
                if (pages > 1 && added == 0) break
                val next = findNextPageUrl(doc, url) ?: break
                val nextHtml = try { PageFetcher.fetch(next, client).html } catch (e: Exception) { break }
                if (nextHtml.isBlank()) break
                html = nextHtml
                url = next
            }
        } finally {
            client.close()
        }
        return merged.values.toList()
    }

    /** Tim URL trang muc luc TIEP THEO tren trang hien tai, neu co. */
    fun findNextPageUrl(doc: Document, currentUrl: String): String? {
        val currentHost = try { URI(currentUrl).host } catch (e: Exception) { null }
        fun usable(abs: String): Boolean {
            if (abs.isBlank() || samePageUrl(abs, currentUrl)) return false
            val host = try { URI(abs).host } catch (e: Exception) { null }
            return currentHost == null || host == null || host.equals(currentHost, true)
        }
        // 1) rel=next tuong minh.
        for (a in doc.select("a[rel~=next][href]")) {
            val abs = a.absUrl("href")
            if (usable(abs)) return abs
        }
        // 2) Link co so trang = trang hien tai + 1 va cung "khuon" URL (chi khac
        //    so trang): mau trang-N / ?page=N pho bien tren cac web truyen.
        val currentPage = pageNumberOf(currentUrl) ?: 1
        for (a in doc.select("a[href]")) {
            val abs = a.absUrl("href")
            if (!usable(abs)) continue
            val n = pageNumberOf(abs) ?: continue
            if (n == currentPage + 1 && stripPageToken(abs) == stripPageToken(currentUrl)) return abs
        }
        // 3) Nhan "trang sau" tron ven ben trong cac khoi phan trang.
        for (a in doc.select(".pagination a[href], .pager a[href], .page-nav a[href], ul.pagination a[href]")) {
            val text = a.text().trim().lowercase()
            val abs = a.absUrl("href")
            if (text in NEXT_TEXTS && usable(abs)) return abs
        }
        return null
    }

    private fun pageNumberOf(url: String): Int? =
        PAGE_TOKEN.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Bo doan so trang khoi URL de so "khuon" cua hai URL phan trang. */
    private fun stripPageToken(url: String): String {
        var u = url.substringBefore('#')
        u = QUERY_PAGE.replace(u, "")
        u = PATH_PAGE.replace(u, "")
        u = BARE_PAGE.replace(u, "")
        return u.trimEnd('/', '?', '&').lowercase()
    }
}

internal fun defaultTocUrl(chapterUrl: String): String? = try {
    val u = URI(chapterUrl)
    val path = (u.path ?: "").trimEnd('/')
    val idx = path.lastIndexOf('/')
    if (idx < 0) null else {
        val last = path.substring(idx + 1)
        val prefix = path.substring(0, idx)
        if (CHUONG_RE.containsMatchIn(last)) "${u.scheme}://${u.authority}$prefix" else null
    }
} catch (e: Exception) { null }

/** metruyenchuvn.com / metruyenchu.com */
class MetruyenchuvnAdapter : BaseSiteAdapter() {
    override val supportedDomains = listOf("metruyenchuvn.com", "metruyenchu.com")

    private val chapterTitleSel = ".current-chapter, h2.heading, .chapter-title, a.chapter-title"
    private val storyTitleSel = ".current-book, h1.truyen-title, .story-title, a.truyen-title"

    override suspend fun getChapter(html: String, url: String): ChapterData {
        val doc = Jsoup.parse(html, url)
        val storyTitle = firstMatch(doc, storyTitleSel)?.text()?.trim() ?: ""
        var title = firstMatch(doc, chapterTitleSel)?.text()?.trim() ?: ""
        title = stripStoryFromTitle(title, storyTitle)
        val contentEl = firstMatch(doc, "#chapter-c, .chapter-c, #chapterbody, .chapter-content, .truyen")
        var content: String
        if (contentEl != null) {
            contentEl.select("h1, h2, h3, .chapter-title, .heading, a.chapter-title, .current-book, .current-chapter").remove()
            content = Cleaner.cleanChapterContent(contentEl.outerHtml())
        } else {
            content = Cleaner.cleanChapterContent(html)
        }
        val nextUrl = resolveNext(doc, "a#next_chap, a.next, a[title*='Ch\u01b0\u01a1ng sau'], a[title*='ch\u01b0\u01a1ng sau']")
        content = TextPipeline.stripLeadingTitles(content, listOf(title, storyTitle))
        return ChapterData(
            title = title.ifBlank { "Ch\u01b0\u01a1ng kh\u00f4ng r\u00f5" },
            content = content,
            chapterUrl = url,
            nextUrl = nextUrl,
            storyTitle = storyTitle
        )
    }

    override suspend fun getStoryMeta(html: String, url: String): StoryMeta {
        val doc = Jsoup.parse(html, url)
        val title = firstMatch(doc, storyTitleSel)?.text()?.trim() ?: ""
        val author = firstMatch(doc, "a[href*='tac-gia'], .author, span[itemprop='author']")?.text()?.trim() ?: ""
        return StoryMeta(title = title, author = author)
    }

    override fun getTocUrl(chapterUrl: String): String? = defaultTocUrl(chapterUrl)

    override suspend fun getChapterList(html: String, url: String): List<ChapterLink> =
        TocPager.collectAll(html, url) { pageHtml, pageUrl -> parseChapterListPage(pageHtml, pageUrl) }

    private fun parseChapterListPage(html: String, url: String): List<ChapterLink> {
        val doc = Jsoup.parse(html, url)
        val out = ArrayList<ChapterLink>()
        for (a in doc.select("ul.list-chapter a[href], .list-chapter a[href]")) {
            val href = a.attr("href")
            if (href.isBlank() || href == "#") continue
            out.add(ChapterLink(a.text().trim(), a.absUrl("href")))
        }
        return out.distinctBy { it.url.trimEnd('/') }
    }
}

/** wikicv.net / wikicv.com */
class WikicvAdapter : BaseSiteAdapter() {
    override val supportedDomains = listOf(
        "wikicv.org",
        "wikicv.net",
        "wikicv.com",
        "truyenwikidich.net",
        "wikidich.info"
    )

    override suspend fun getChapter(html: String, url: String): ChapterData {
        val doc = Jsoup.parse(html, url)
        var storyTitle = ""
        var title = ""
        val bookTitles = doc.select(".book-title").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (bookTitles.isNotEmpty()) {
            storyTitle = bookTitles[0]
            for (t in bookTitles.drop(1)) { if (t.isNotEmpty() && t != storyTitle) { title = t; break } }
        }
        if (title.isBlank()) {
            firstMatch(doc, "h1.entry-title, h2.entry-title, .chapter-title, h1.name-chapter, .heading-chapter")
                ?.text()?.trim()?.let { title = it }
        }
        if (storyTitle.isBlank()) {
            val st = firstMatch(doc, ".story-name, .truyen-title, a.story-name, h1.story-title")
            if (st != null) storyTitle = st.text().trim()
            else {
                val bc = firstMatch(doc, ".breadcrumb, nav[aria-label='breadcrumb']")
                if (bc != null) {
                    val links = bc.select("a")
                    if (links.size >= 2) storyTitle = links.last()!!.text().trim()
                }
            }
        }
        val contentEl = firstMatch(doc, "#bookContentBody, #bookContent, .content-body-wrapper, .entry-content, .text-content, #content, .chapter-content, .reading-content, .content-chapter")
        var content: String
        if (contentEl != null) {
            contentEl.select("h1, h2, h3, .chapter-title, .heading, .name-chapter, .heading-chapter, .entry-title, .book-title").remove()
            content = Cleaner.cleanChapterContent(contentEl.outerHtml())
        } else {
            content = Cleaner.cleanChapterContent(html)
        }
        val nextUrl = resolveNext(doc, "a.next, a[rel='next'], a.next-chap, a.btn-next", extraHrefHint = "chuong-tiep")
        content = TextPipeline.stripLeadingTitles(content, listOf(title, storyTitle))
        return ChapterData(
            title = title.ifBlank { "Ch\u01b0\u01a1ng kh\u00f4ng r\u00f5" },
            content = content,
            chapterUrl = url,
            nextUrl = nextUrl,
            storyTitle = storyTitle
        )
    }

    override suspend fun getStoryMeta(html: String, url: String): StoryMeta {
        val doc = Jsoup.parse(html, url)
        var title = firstMatch(doc, ".cover-info h2, .book-title")?.text()?.trim() ?: ""
        if (title.isBlank()) firstMatch(doc, ".story-name, .truyen-title, h1.story-title")?.text()?.trim()?.let { title = it }
        // /tac-gia (không có dấu / cuối) là mục menu "Tác giả"; liên kết
        // /tac-gia/<slug> bên trong cover-info mới là tên tác giả thật.
        val author = firstMatch(doc, ".cover-info a[href^='/tac-gia/'], .book-author, .author, .story-author")
            ?.text()?.trim() ?: ""
        val cover = firstMatch(doc, "div.book-info img, .book-cover img, .book-img img, .cover-col img, img.cover")
            ?.let { image ->
                val raw = listOf("src", "data-src", "data-original", "data-lazy-src")
                    .firstNotNullOfOrNull { attr -> image.attr(attr).trim().takeIf { it.isNotBlank() && !it.startsWith("data:") } }
                    .orEmpty()
                image.absUrl("src").ifBlank {
                    runCatching { URI(url).resolve(raw).toString() }.getOrDefault(raw)
                }
            } ?: ""
        val description = firstMatch(doc, "div.book-desc-detail, .book-desc, .description, #bookIntro")
            ?.text()?.trim() ?: ""
        return StoryMeta(title = title, author = author, coverUrl = cover, description = description)
    }

    override fun getTocUrl(chapterUrl: String): String? = defaultTocUrl(chapterUrl)

    override suspend fun getChapterList(html: String, url: String): List<ChapterLink> =
        TocPager.collectAll(html, url) { pageHtml, pageUrl -> parseChapterListPage(pageHtml, pageUrl) }

    private fun parseChapterListPage(html: String, url: String): List<ChapterLink> {
        val doc = Jsoup.parse(html, url)
        val out = ArrayList<ChapterLink>()
        for (a in doc.select("li.chapter-name a[href], li.chapter-name a[data-href], ul.list-chapter a[href], .list-chapter a[href], .chapter-list a[href]")) {
            val href = a.attr("href").ifBlank { a.attr("data-href") }
            if (href.isBlank() || href == "#") continue
            val absolute = if (a.hasAttr("href") && a.attr("href").isNotBlank()) {
                a.absUrl("href")
            } else {
                a.absUrl("data-href")
            }
            out.add(ChapterLink(a.text().trim(), absolute.ifBlank { href }))
        }
        return out.distinctBy { it.url.trimEnd('/') }
    }
}

/** Registry — tu dong tim adapter theo domain. Them site moi chi can them 1 adapter. */
object SiteRegistry {
    private val adapters = mutableListOf<BaseSiteAdapter>(
        MetruyenchuvnAdapter(),
        WikicvAdapter()
    )

    fun getAdapterForUrl(url: String): BaseSiteAdapter? {
        val domain = try { (URI(url).host ?: "").lowercase() } catch (e: Exception) { "" }
        // 1) Adapter cung (neu co) — dieu chinh rieng cho vai site.
        // 2) Adapter dieu khien bang parser.json dong goi trong app.
        // 3) Adapter tong quat (readability) cho MOI link la.
        val base: BaseSiteAdapter = adapters.firstOrNull { it.canHandle(domain) }
            ?: SiteConfigStore.findForUrl(url)?.let { ConfigSiteAdapter(it) }
            ?: GenericSiteAdapter()
        // 0) UU TIEN CAO NHAT: bo nguon JS tai tu kho GitHub parser source repository.
        //    Nguon tai ve moi hon nen dung truoc; neu script tra ve rong thi tu
        //    quay lai `base` ben tren nen khong bao gio te hon truoc.
        val jsSource = try {
            vn.tramtruyen.reader.engine.catalog.ParserSourceStore.findForUrl(url)
        } catch (e: Exception) { null }
        if (jsSource != null) return vn.tramtruyen.reader.engine.catalog.JsSiteAdapter(jsSource, base)
        return base
    }

    fun registerAdapter(a: BaseSiteAdapter) { adapters.add(a) }

    fun listSupportedSites(): List<String> =
        (adapters.flatMap { it.supportedDomains } + SiteConfigStore.allDomains() +
            (try { vn.tramtruyen.reader.engine.catalog.ParserSourceStore.allDomains() } catch (e: Exception) { emptyList() })).distinct()
}
