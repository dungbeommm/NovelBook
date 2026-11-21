package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.TextPipeline
import vn.tramtruyen.reader.engine.Cleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * BO TRICH XUAT TONG QUAT (readability) — dung cho LINK LA (site chua co trong parser.json)
 * hoac khi selector cau hinh khong lay duoc noi dung.
 *
 * Muc tieu: bat ky link truyen nao nguoi dung NHAP MOI cung co the doc duoc, khong con
 * bi tu choi "khong ho tro site nay".
 */
object GenericExtractor {

    // Cac selector vung noi dung pho bien tren cac web truyen.
    private const val COMMON_CONTENT =
        "#chapter-c, .chapter-c, #chapter-content, .chapter-content, .reading-content, " +
        ".entry-content, .content-chapter, .chapter-body, #chapterbody, #chr-content, " +
        ".chr-c, #content, .text-content, .content-body-wrapper, article .content, " +
        ".post-content, .article-content, .box-chap, .reader-content"

    private val BAD_CLASS = Regex(
        "comment|footer|header|menu|nav|sidebar|breadcrumb|share|social|advert|\\bads?\\b|" +
        "banner|related|recommend|widget|pagination", RegexOption.IGNORE_CASE
    )
    private val NOISE_TEXT = Regex(
        "đăng nhập|đăng ký|bình luận|quảng cáo|tải ứng dụng|chia sẻ|truyện đề cử|" +
            "login|register|comment|advertisement|related posts|cookie policy",
        RegexOption.IGNORE_CASE,
    )

    /** Tim vung chua noi dung: cong diem cac doan <p> cho phan tu cha. */
    fun bestContentElement(doc: Document): Element? {
        // Không lấy phần tử khớp đầu tiên: nhiều trang có một #content ngắn ở
        // đầu và nội dung chương thật nằm trong phần tử khớp sau. Chấm điểm tất
        // cả ứng viên giúp tránh lấy menu, bình luận hoặc đoạn giới thiệu.
        val candidates = LinkedHashSet<Element>()
        try { candidates.addAll(doc.select(COMMON_CONTENT)) } catch (_: Exception) {}
        for (p in doc.select("p")) {
            val t = p.text().trim()
            if (t.length < 20) continue
            val parent = p.parent() ?: continue
            candidates.add(parent)
            parent.parent()?.let(candidates::add)
        }
        candidates.addAll(doc.select("article, main, [role=main], section"))
        return candidates
            .asSequence()
            .filter { !isBad(it) && it.text().trim().length >= 80 }
            .maxByOrNull(::elementScore)
    }

    private fun isBad(el: Element): Boolean {
        val idcls = (el.id() + " " + el.className())
        return idcls.isNotBlank() && BAD_CLASS.containsMatchIn(idcls)
    }

    private fun elementScore(el: Element): Int {
        val text = el.text().trim()
        if (text.length < 80) return Int.MIN_VALUE
        val paragraphCount = el.select("> p, > div > p").count { it.text().trim().length >= 20 }
        val breaks = el.select("br").size
        val punctuation = text.count { it == '.' || it == '!' || it == '?' || it == '。' }
        val linkLength = el.select("a").sumOf { it.text().length }
        val badChildren = el.select(
            "nav, footer, header, aside, form, .comment, .comments, .advert, .ads, .related, .share",
        ).size
        val semanticBonus = when (el.tagName()) {
            "article", "main" -> 450
            else -> 0
        }
        val chapterBonus = if (
            Regex("chapter|chap|reader|reading|entry-content|story|novel|truyen", RegexOption.IGNORE_CASE)
                .containsMatchIn(el.id() + " " + el.className())
        ) 500 else 0
        val rawScore = text.length.coerceAtMost(30_000) +
            paragraphCount * 120 + breaks.coerceAtMost(100) * 18 +
            punctuation.coerceAtMost(300) * 4 + semanticBonus + chapterBonus -
            linkLength * 3 - badChildren * 350
        // Kieu Aufi/Readability: nhan them (1 - mat do link); khoi cang nhieu
        // link thi diem cang bi keo xuong, khoi khong link giu nguyen diem.
        val linkDensity = if (text.isEmpty()) 0.0
            else (linkLength.toDouble() / text.length).coerceIn(0.0, 1.0)
        return (rawScore * (1.0 - linkDensity)).toInt()
    }

    /** Điểm chất lượng dùng để so sánh kết quả parser nguồn và DOM tổng quát. */
    fun contentQualityScore(text: String): Int {
        val clean = text.trim()
        if (clean.isEmpty()) return Int.MIN_VALUE
        val lines = clean.lines().map(String::trim).filter(String::isNotEmpty)
        val uniqueRatio = if (lines.isEmpty()) 0.0 else lines.distinct().size.toDouble() / lines.size
        val punctuation = clean.count { it == '.' || it == '!' || it == '?' || it == '。' }
        val noise = NOISE_TEXT.findAll(clean).count()
        return clean.length.coerceAtMost(40_000) +
            lines.size.coerceAtMost(400) * 15 +
            punctuation.coerceAtMost(500) * 3 +
            (uniqueRatio * 500).toInt() - noise * 450
    }

    fun chooseBetter(sourceText: String, genericText: String): String {
        val source = sourceText.trim()
        val generic = genericText.trim()
        if (source.length < 50) return generic
        if (generic.length < 50) return source
        val sourceScore = contentQualityScore(source)
        val genericScore = contentQualityScore(generic)
        // Parser riêng thắng khi hai kết quả gần nhau vì selector theo nguồn
        // thường loại watermark tốt hơn. DOM chỉ thay thế khi tốt hơn rõ rệt.
        return if (genericScore > (sourceScore * 1.12).toInt()) generic else source
    }

    /** Trich noi dung chuong dang text da lam sach. Luon tra ve chuoi (co the rong). */
    fun extractContent(doc: Document): String {
        // Trang rac (Cloudflare/bot check/tuong dang nhap) khong phai noi dung
        // chuong: tra rong de tang tren doi chien luoc (WebView) thay vi doc rac.
        if (SuperExtractor.junkKind(doc.outerHtml()) != null) return ""
        // Lam viec tren ban sao: xoa rac bang TRASH_SELECTORS cua Aufi truoc khi
        // chon vung noi dung, va khong lam hong DOM goc (dieu huong con dung lai).
        val work = doc.clone()
        SuperExtractor.removeTrash(work)
        // Tang 1 (Aufi): chuoi selector theo thu tu rule + leo thang.
        val dedicated = work.selectFirst(".tdai-content, section[itemprop=text], .prose-novel")
        if (dedicated != null && dedicated.text().any(Char::isLetterOrDigit)) {
            return Cleaner.cleanChapterContent(dedicated.outerHtml())
        }
        val chainEl = SuperExtractor.selectContentElement(work)
        val chainLen = chainEl?.text()?.trim()?.length ?: 0
        var el = chainEl
        // Tang 2: mat do van ban — thang khi dai hon han selector (rule loi thoi).
        if (chainEl == null || chainLen < AufiRules.CONTENT_ENOUGH_LEN) {
            val densityEl = bestContentElement(work)
            val densityLen = densityEl?.text()?.trim()?.length ?: 0
            if (densityEl != null && (chainEl == null ||
                    (densityLen >= chainLen * AufiRules.ESCALATE_RATIO &&
                        densityLen - chainLen >= AufiRules.ESCALATE_GAP))
            ) {
                el = densityEl
            }
        }
        return if (el != null) {
            val copy = el.clone()
            try {
                copy.select(
                    "h1, h2, h3, .chapter-title, .heading, nav, footer, aside, form, " +
                        ".comment, .comments, .advert, .ads, .related, .share",
                ).remove()
            } catch (_: Exception) {}
            Cleaner.cleanChapterContent(copy.outerHtml())
        } else {
            Cleaner.cleanChapterContent(work.body().outerHtml())
        }
    }

    /** Prefer the chapter heading to the browser tab title. */
    fun guessTitle(doc: Document): String {
        for (sel in listOf(".tdai-chapter-title", ".chapter-title", ".chapter-name", ".chr-title", "h1", "h2")) {
            val t = doc.selectFirst(sel)?.text()?.trim()
            if (!t.isNullOrBlank()) return t
        }
        val pageTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return trimSite(pageTitle.ifBlank { doc.title() }.trim())
    }

    private val chapterLabel = Regex(
        """^(?:(?:chương|chuong|chapter|hồi|tập)\s*\d+|第\s*[0-9一二三四五六七八九十百千零〇两]+\s*[章回])""",
        RegexOption.IGNORE_CASE,
    )
    private val conversionLabel = Regex("""\s+Dịch\s+AI\s*Convert\s*$""", RegexOption.IGNORE_CASE)

    fun cleanStoryTitle(value: String, chapterTitle: String = ""): String {
        val title = conversionLabel.replace(value.trim(), "").trim()
        return title.takeUnless {
            it.equals(chapterTitle.trim(), true) || it.equals("Trang chủ", true) ||
                it.equals("Home", true) || chapterLabel.containsMatchIn(it)
        }.orEmpty()
    }

    fun looksLikeChapterPage(html: String, url: String, chapter: ChapterData): Boolean {
        val numberInPath = Regex("""(?:chuong|chapter)[-_/](\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(url.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)?.toLongOrNull()
        val numberInTitle = Regex("""^(?:chương|chuong|chapter)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(chapter.title.trim())?.groupValues?.get(1)?.toLongOrNull()
        if (numberInPath != null && numberInTitle != null && numberInPath != numberInTitle) return false
        if (chapterLabel.containsMatchIn(chapter.title.trim()) ||
            CHAP_HREF.containsMatchIn(url.substringBefore('?')) ||
            !chapter.nextUrl.isNullOrBlank() && !chapter.prevUrl.isNullOrBlank()) return true
        val doc = Jsoup.parse(html, url)
        return doc.selectFirst(
            "section[itemprop=text], .prose-novel, .tdai-content, #chapter-content, .chapter-content, " +
                "#chapter-c, .chapter-c, .chapter-body, .reader-content",
        ) != null
    }

    /** Prefer a book label or a link back to the book in the chapter breadcrumb. */
    fun guessStoryTitle(doc: Document): String {
        for (sel in listOf("meta[property=og:novel:book_name]", ".tdai-book-title",
            ".story-title", ".truyen-title", ".book-title", ".novel-title")) {
            val element = doc.selectFirst(sel) ?: continue
            val value = if (element.normalName() == "meta") element.attr("content") else element.text()
            cleanStoryTitle(value).takeIf { it.isNotBlank() }?.let { return it }
        }
        val crumbs = doc.select(
            ".breadcrumb a[href], nav[aria-label=breadcrumb] a[href], .breadcrumbs a[href], [itemtype$=BreadcrumbList] a[href]",
        )
        for (crumbIndex in crumbs.lastIndex downTo 0) {
            val crumb = crumbs[crumbIndex]
            val href = crumb.absUrl("href").substringBefore('#').trimEnd('/')
            if (href.isBlank() || href == doc.location().substringBefore('#').trimEnd('/') ||
                CHAP_HREF.containsMatchIn(href) || href.substringAfter("://").substringAfter('/').isBlank() ||
                crumb.hasAttr("aria-current")) continue
            cleanStoryTitle(crumb.text()).takeIf { it.isNotBlank() }?.let { return it }
        }
        return cleanStoryTitle(doc.selectFirst("h1.title")?.text().orEmpty())
    }

    /** Tim link "chuong sau" theo rel/text pho bien. */
    fun guessNextUrl(doc: Document): String? {
        // uu tien cac selector next quen thuoc
        for (sel in listOf("a#next_chap", "a.next", "a[rel=next]", "a.next-chap", "a.btn-next", "a.chapter-next")) {
            val a = doc.selectFirst(sel)
            val href = a?.attr("href")
            if (!href.isNullOrBlank() && href != "#" && !href.contains("javascript:")) return a.absUrl("href")
        }
        for (a in doc.select("a[href]")) {
            val text = a.text().trim().lowercase()
            val title = a.attr("title").lowercase()
            if (text.contains("ch\u01b0\u01a1ng sau") || text.contains("ch\u01b0\u01a1ng ti\u1ebfp") ||
                text == "next" || text.contains("sau \u2192") || text.contains("ti\u1ebfp \u2192") ||
                text.contains("next chapter") || text.contains("下一章") || text.contains("下章") ||
                title.contains("ch\u01b0\u01a1ng sau") || title.contains("ch\u01b0\u01a1ng ti\u1ebfp")) {
                val href = a.attr("href")
                if (href.isNotBlank() && href != "#" && !href.contains("javascript:")) return a.absUrl("href")
            }
        }
        // Du phong kieu Aufi: cascade tu khoa da ngon ngu (83 tu khoa next)
        // + chuong ke trong danh sach chuong ngay tren trang.
        return SuperExtractor.findNextByCascade(doc, doc.location())
    }

    // Link co dang chuong: .../chuong-12, /chapter-3, /chap-5, /c/123, tap-7...
    private val CHAP_HREF = Regex("(chuong|chapter|chap|episode|/c/|-c-|tap)[-_/]?\\d", RegexOption.IGNORE_CASE)
    private val CHAP_TEXT = Regex(
        "(chương|chapter|chap|episode|tập)\\s*\\d+|第\\s*[0-9一二三四五六七八九十百千零〇两]+\\s*[章回]",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_CONTAINERS = listOf(
        ".list-chapter", ".chapter-list", "#chapter-list", "#list-chapter", "ul.list-chapter",
        ".chapters", ".list-chapters", ".book-chapters", ".chapter_list", ".chapter-listing",
        ".danh-sach-chuong", "#danh-sach-chuong", ".ds-chuong", ".module-chapters"
    )

    /**
     * MUC LUC tong quat: gom cac link chuong tren 1 trang (gioi thieu / muc luc).
     * Uu tien cac container danh sach quen thuoc; neu khong co thi loc link theo mau URL chuong.
     * Giu THU TU xuat hien va loai trung.
     */
    fun guessChapterLinks(doc: Document): List<ChapterLink> {
        var anchors: List<Element>? = null
        for (sel in LIST_CONTAINERS) {
            val c = try { doc.select("$sel a[href]") } catch (_: Exception) { null } ?: continue
            if (c.size >= 3) { anchors = c.toList(); break }
        }
        val restrictToPattern = anchors == null
        val list = anchors ?: doc.select("a[href]").toList()
        val out = LinkedHashMap<String, String>() // url -> title
        for (a in list) {
            val href = a.attr("href")
            if (href.isBlank() || href == "#" || href.startsWith("javascript:")) continue
            val abs = a.absUrl("href").ifBlank { href }
            val title = a.text().trim().ifBlank { a.attr("title").trim() }
            if (restrictToPattern &&
                !CHAP_HREF.containsMatchIn(abs) &&
                !CHAP_TEXT.containsMatchIn(title)
            ) continue
            if (abs !in out) out[abs] = title
        }
        var links = out.map { ChapterLink(it.value.ifBlank { "Ch\u01b0\u01a1ng" }, it.key) }
        links = preferStoryPrefix(links, doc.location())
        links = fixReadingOrder(links)
        return links
    }

    /**
     * Khi biet URL trang truyen (doc.location): uu tien cac link chuong nam DUOI
     * duong dan cua trang truyen. Container danh sach co the vo tinh gom ca
     * widget "truyen moi cap nhat" cua truyen khac; chi loc khi nhom dung duong
     * dan con du lon (>= 3) de khong lam mat chuong o cac site co cau truc la.
     */
    private fun preferStoryPrefix(links: List<ChapterLink>, storyUrl: String): List<ChapterLink> {
        val storyPath = try { java.net.URI(storyUrl).path } catch (e: Exception) { null }
            ?.trimEnd('/')?.takeIf { it.length > 1 } ?: return links
        val inside = links.filter { ch ->
            val p = try { java.net.URI(ch.url).path } catch (e: Exception) { null } ?: return@filter false
            p.startsWith("$storyPath/")
        }
        return if (inside.size >= 3) inside else links
    }

    /**
     * Nhieu site liet ke chuong MOI NHAT TRUOC (giam dan). Phat hien qua so
     * chuong cua muc dau > muc cuoi thi dao lai thanh tang dan, de "doc tu dau"
     * mo dung chuong 1 thay vi chuong moi nhat (lo het noi dung).
     */
    private fun fixReadingOrder(links: List<ChapterLink>): List<ChapterLink> {
        if (links.size < 2) return links
        val first = chapterNumberOf(links.first()) ?: return links
        val last = chapterNumberOf(links.last()) ?: return links
        return if (first > last) links.reversed() else links
    }

    private val CHAP_NUM = Regex(
        "(ch\u01b0\u01a1ng|chuong|chapter|chap|t\u1eadp|tap|episode)[-_\\s]*(\\d+)",
        RegexOption.IGNORE_CASE,
    )

    private fun chapterNumberOf(link: ChapterLink): Int? {
        CHAP_NUM.find(link.title)?.let { return it.groupValues[1].toIntOrNull() }
        CHAP_NUM.find(link.url)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** Tu trang gioi thieu -> URL CHUONG DAU (nut "doc tu dau" hoac link chuong dau danh sach). */
    fun guessFirstChapterUrl(doc: Document): String? {
        for (sel in listOf(
            "#firstChapter", "a#first_chap", "a.first-chapter", "a.read-first", "a.btn-read-first",
            "a.read-now", "a.btn-read", ".control-btns a.orange-btn", "#init-links a"
        )) {
            val a = try { doc.selectFirst(sel) } catch (_: Exception) { null }
            val href = a?.attr("href")
            if (!href.isNullOrBlank() && href != "#" && !href.contains("javascript:")) return a.absUrl("href")
        }
        return guessChapterLinks(doc).firstOrNull()?.url
    }

    private fun trimSite(title: String): String {
        // Cat duoi dang "Ten chuong - Ten site" / "... | Site".
        val parts = title.split(Regex("\\s[\\-\\|\u00bb\u2013\u2014]\\s"))
        return if (parts.size >= 2 && parts[0].trim().length >= 4) parts[0].trim() else title
    }
}

/**
 * Adapter CATCH-ALL cho MOI link (ke ca site chua khai bao). Luon tra ve ket qua co gang
 * tot nhat thay vi tu choi. Nho vay "link moi nhap" luon dung duoc.
 */
class GenericSiteAdapter : BaseSiteAdapter() {
    override val supportedDomains: List<String> = emptyList()

    override suspend fun getChapter(html: String, url: String): ChapterData {
        val doc = Jsoup.parse(html, url)
        var storyTitle = GenericExtractor.guessStoryTitle(doc)
        var title = GenericExtractor.guessTitle(doc)
        var content = GenericExtractor.extractContent(doc)
        content = TextPipeline.stripLeadingTitles(content, listOf(title, storyTitle))
        // Giai ma font chong crawl (Fanqie/Tomato) neu noi dung bi ma hoa PUA.
        if (FontObfuscationDecoder.hasObfuscatedChars(content)) {
            content = FontObfuscationDecoder.decode(content)
            title = FontObfuscationDecoder.decode(title)
            storyTitle = FontObfuscationDecoder.decode(storyTitle)
        }
        return ChapterData(
            title = title.ifBlank { "Ch\u01b0\u01a1ng kh\u00f4ng r\u00f5" },
            content = content,
            chapterUrl = url,
            nextUrl = GenericExtractor.guessNextUrl(doc),
            prevUrl = SuperExtractor.findPrevByCascade(doc, url),
            storyTitle = storyTitle,
        )
    }

    override suspend fun getStoryMeta(html: String, url: String): StoryMeta {
        val doc = Jsoup.parse(html, url)
        val title = GenericExtractor.guessStoryTitle(doc).ifBlank { GenericExtractor.guessTitle(doc) }
        return StoryMeta(title = title)
    }

    override suspend fun getChapterList(html: String, url: String): List<ChapterLink> =
        TocPager.collectAll(html, url) { pageHtml, pageUrl ->
            GenericExtractor.guessChapterLinks(Jsoup.parse(pageHtml, pageUrl))
        }

    override fun getTocUrl(chapterUrl: String): String? = defaultTocUrl(chapterUrl)

    override suspend fun getFirstChapterUrl(html: String, url: String): String? =
        GenericExtractor.guessFirstChapterUrl(Jsoup.parse(html, url))
}
