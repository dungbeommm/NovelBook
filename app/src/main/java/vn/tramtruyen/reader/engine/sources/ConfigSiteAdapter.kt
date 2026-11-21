package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.TextPipeline
import vn.tramtruyen.reader.engine.Cleaner
import vn.tramtruyen.reader.engine.ContentExtractionMode
import vn.tramtruyen.reader.engine.ReadingPreferences
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Adapter DIEU KHIEN BANG CAU HINH — dung selector tu parser.json cho MOI site
 * (thay vi phai viet 1 class Kotlin cho tung site). Nho vay app ho tro tat ca ~47 web
 * ma app goc ho tro, va them site moi chi can them 1 muc trong parser.json.
 *
 * Luong lam sach giu dung thu tu cua app goc:
 *   html_removes (xoa element) -> lay text vung content -> content_removes (cat doan)
 *   -> content_replaces (xoa chuoi) -> stripLeadingTitles.
 */
class ConfigSiteAdapter(private val cfg: SiteConfig) : BaseSiteAdapter() {

    override val supportedDomains: List<String> = cfg.allDomains

    override suspend fun getChapter(html: String, url: String): ChapterData {
        // Trang rac (Cloudflare/bot check/tuong dang nhap/web sap): dung som
        // kieu SuperCrawler — tra noi dung rong de tang tren doi chien luoc,
        // tranh doc nham trang thu thach thanh noi dung chuong.
        if (SuperExtractor.junkKind(html) != null) {
            return ChapterData(
                title = "Chương không rõ",
                content = "",
                chapterUrl = url,
                nextUrl = null,
                prevUrl = null,
                storyTitle = "",
            )
        }
        val doc = Jsoup.parse(html, url)

        // 1) html_removes: xoa cac element rac theo selector (vd ruby/rt phien am).
        for (sel in cfg.htmlRemoves) {
            if (sel.isBlank()) continue
            try { doc.select(sel).remove() } catch (_: Exception) {}
        }

        // TEN TRUYEN + TEN CHUONG: theo dung cach app goc (selector+value hoac cat chuoi).
        var storyTitle = GenericExtractor.cleanStoryTitle(textField(doc, html, cfg.title))
        var chapterTitle = textField(doc, html, cfg.chapter)

        // 2) Lay vung NOI DUNG chuong.
        val sourceContent = when {
            // 2a) Kieu cat chuoi (vd noi dung nam trong <script>): plnovel...
            cfg.content?.isStringSlice == true -> Cleaner.cleanChapterContent(sliceHtml(html, cfg.content!!))
            // 2b) Kieu selector.
            else -> {
                val contentEl = elementOf(doc, cfg.content)
                if (contentEl != null) {
                    contentEl.select("h1, h2, h3, .chapter-title, .heading").remove()
                    Cleaner.cleanChapterContent(contentEl.outerHtml())
                } else {
                    ""
                }
            }
        }

        // A working source selector defines the prose boundary. Broader DOM often includes page chrome.
        val genericContent by lazy { GenericExtractor.extractContent(doc) }
        var content = when (ReadingPreferences.extractionMode) {
            ContentExtractionMode.SOURCE_FIRST, ContentExtractionMode.SMART ->
                sourceContent.ifBlank { genericContent }
            ContentExtractionMode.GENERIC_FIRST ->
                if (genericContent.trim().length >= 50) genericContent else sourceContent
        }
        // TEN bi thieu -> doan bang bo tong quat (og:title / breadcrumb / <title>).
        if (chapterTitle.isBlank()) chapterTitle = GenericExtractor.guessTitle(doc)
        if (storyTitle.isBlank()) storyTitle = GenericExtractor.guessStoryTitle(doc)

        // 3) content_removes: cat doan giua cac dau moc (quang cao / watermark).
        content = applyContentRemoves(content, cfg.contentRemoves)
        // 4) content_replaces: xoa cac chuoi rac.
        for (r in cfg.contentReplaces) if (r.isNotEmpty()) content = content.replace(r, "")
        // 5) Bo TEN TRUYEN / TEN CHUONG bi lap o dau noi dung (tranh trung khi da co heading).
        content = TextPipeline.stripLeadingTitles(content, listOf(chapterTitle, storyTitle))
        // 6) apply_word_segmentation: tach am tiet/tu cho site co noi dung dinh lien
        //    (vd noi dung dich may tho). Chi chay khi parser.json bat co nay.
        if (cfg.applyWordSegmentation) {
            content = vn.tramtruyen.reader.engine.TextPipeline.segment(content)
        }

        // 7) Giai ma font chong crawl (Fanqie/Tomato) neu noi dung bi ma hoa PUA.
        if (FontObfuscationDecoder.hasObfuscatedChars(content)) {
            content = FontObfuscationDecoder.decode(content)
            chapterTitle = FontObfuscationDecoder.decode(chapterTitle)
            storyTitle = FontObfuscationDecoder.decode(storyTitle)
        }

        val nextUrl = urlField(doc, html, cfg.nextButton, url) ?: GenericExtractor.guessNextUrl(doc)
        val prevUrl = urlField(doc, html, cfg.preButton, url)
        return ChapterData(
            title = chapterTitle.ifBlank { "Ch\u01b0\u01a1ng kh\u00f4ng r\u00f5" },
            content = content,
            chapterUrl = url,
            nextUrl = nextUrl,
            prevUrl = prevUrl,
            storyTitle = storyTitle,
        )
    }

    override suspend fun getStoryMeta(html: String, url: String): StoryMeta {
        val doc = Jsoup.parse(html, url)
        val title = GenericExtractor.cleanStoryTitle(textField(doc, html, cfg.title))
            .ifBlank { GenericExtractor.guessStoryTitle(doc) }
        return StoryMeta(title = title)
    }

    /** Suy ra URL trang muc luc tu URL chuong (bo doan /chuong-N o cuoi). */
    override fun getTocUrl(chapterUrl: String): String? = defaultTocUrl(chapterUrl)

    /** MUC LUC: gom link chuong tu trang gioi thieu/muc luc, di tiep cac trang
     * muc luc sau neu con link phan trang (TocPager). */
    override suspend fun getChapterList(html: String, url: String): List<ChapterLink> =
        TocPager.collectAll(html, url) { pageHtml, pageUrl ->
            GenericExtractor.guessChapterLinks(Jsoup.parse(pageHtml, pageUrl))
        }

    /**
     * Tu trang GIOI THIEU -> URL CHUONG DAU.
     * Uu tien selector `detail_first_chapter`; ke den `detail_first_chapter_js` dang
     * "click:<selector>" (doc href/data-href cua nut); cuoi cung fallback tong quat.
     */
    override suspend fun getFirstChapterUrl(html: String, url: String): String? {
        val doc = Jsoup.parse(html, url)
        urlField(doc, html, cfg.detailFirstChapter, url)?.let { return it }
        cfg.detailFirstChapterJs?.let { js ->
            val sel = js.substringAfter("click:", "").trim()
            if (sel.isNotEmpty()) {
                val el = try { doc.selectFirst(sel) } catch (_: Exception) { null }
                if (el != null) {
                    for (attr in listOf("href", "data-href", "data-url")) {
                        val raw = el.attr(attr)
                        if (raw.isNotBlank() && raw != "#" && !raw.startsWith("javascript:"))
                            return absUrl(url, el.absUrl(attr).ifBlank { raw })
                    }
                }
            }
        }
        return GenericExtractor.guessFirstChapterUrl(doc)
    }

    // ---- helpers dua tren FieldSel (selector+value HOAC cat chuoi start/middle/end) ----

    /** Lay TEXT cua 1 truong: uu tien cat chuoi; nguoc lai selector + doc thuoc tinh "value". */
    private fun textField(doc: Document, html: String, f: FieldSel?): String {
        if (f == null) return ""
        if (f.isStringSlice) return Jsoup.parse(sliceHtml(html, f)).text().trim()
        val el = elementOf(doc, f) ?: return ""
        val v = f.value
        return when {
            v.isNullOrBlank() || v == "text" -> el.text().trim()
            v == "href" -> el.absUrl("href").ifBlank { el.attr("href") }.trim()
            else -> {
                // "title", "data-...", v.v. -> doc dung thuoc tinh do.
                val a = el.attr(v).trim()
                a.ifBlank { el.text().trim() }
            }
        }
    }

    /** Lay URL cua 1 truong (next/prev): cat chuoi hoac selector+thuoc tinh, roi tuyet doi hoa. */
    private fun urlField(doc: Document, html: String, f: FieldSel?, baseUrl: String): String? {
        if (f == null) return null
        if (f.isStringSlice) {
            val raw = sliceHtml(html, f).trim()
            if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:")) return null
            return absUrl(baseUrl, raw)
        }
        return attrOf(doc, f)
    }

    /** Cat chuoi tho tren HTML: doan sau `start` (roi sau `middle` neu co) toi truoc `end`. */
    private fun sliceHtml(html: String, f: FieldSel): String {
        val start = f.start ?: return ""
        var i = html.indexOf(start)
        if (i < 0) return ""
        i += start.length
        val mid = f.middle
        if (!mid.isNullOrEmpty()) {
            val m = html.indexOf(mid, i)
            if (m < 0) return ""
            i = m + mid.length
        }
        val end = f.end
        val j = if (end.isNullOrEmpty()) html.length else html.indexOf(end, i).let { if (it < 0) html.length else it }
        if (j < i) return ""
        return html.substring(i, j)
    }

    private fun absUrl(base: String, href: String): String =
        try { java.net.URI(base).resolve(href.trim()).toString() } catch (e: Exception) { href.trim() }

    private fun elementOf(doc: Document, f: FieldSel?): Element? {
        val sel = f?.selector ?: return null
        return try {
            val els = doc.select(sel)
            if (els.isEmpty()) return null
            val idx = f.elementIndexed ?: 0
            if (idx in els.indices) els[idx] else els.first()
        } catch (e: Exception) { null }
    }

    private fun attrOf(doc: Document, f: FieldSel?): String? {
        val el = elementOf(doc, f) ?: return null
        val attr = if (f?.value.isNullOrBlank()) "href" else f!!.value!!
        val raw = el.attr(attr)
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:")) return null
        val abs = el.absUrl(attr)
        return abs.ifBlank { raw }
    }

    private fun applyContentRemoves(text: String, removes: List<ContentRemove>): String {
        if (removes.isEmpty()) return text
        var t = text
        for (r in removes) {
            val s = r.start
            if (s.isNullOrEmpty()) continue
            val end = r.end
            if (end.isNullOrEmpty()) {
                val idx = t.indexOf(s)
                if (idx >= 0) t = t.substring(0, idx)
            } else {
                var guard = 0
                while (guard++ < 200) {
                    val a = t.indexOf(s)
                    if (a < 0) break
                    val b = t.indexOf(end, a + s.length)
                    if (b < 0) break
                    t = t.substring(0, a) + t.substring(b + end.length)
                }
            }
        }
        return t.trim()
    }
}
