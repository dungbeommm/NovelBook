package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.*
import java.net.URI
import java.io.File

/**
 * Cau hinh 1 site — port cua assets/parser.json trong legacy parser format.
 * Moi site khai bao CACH LAY TRANG (html/webview), selector, JS nap noi dung va cac
 * luat lam sach (content_removes / content_replaces / html_removes).
 */

/**
 * 1 truong chon phan tu. Ho tro 2 kieu (giong app goc):
 *  A) CSS selector: selector + element_indexed + value
 *     - value = null hoac "text" -> lay text; "href"/"title"/... -> lay THUOC TINH do.
 *  B) Cat chuoi tho tren HTML: start + middle + end (dung khi noi dung/ten nam trong
 *     script hoac cho selector khong voi toi). Lay doan sau `start` (roi sau `middle` neu co)
 *     cho toi truoc `end`.
 */
data class FieldSel(
    val selector: String? = null,
    val value: String? = null,          // ten thuoc tinh can doc (vd "href", "title"); null/"text" = lay text
    val elementIndexed: Int? = null,    // chon phan tu thu may khi selector khop nhieu
    val start: String? = null,          // moc bat dau (kieu cat chuoi)
    val middle: String? = null,         // moc giua (tuy chon)
    val end: String? = null,            // moc ket thuc
) {
    val isStringSlice: Boolean get() = !start.isNullOrEmpty()
}

/** 1 luat cat text theo dau moc (giua start...end, hoac tu start toi het neu thieu end). */
data class ContentRemove(val start: String? = null, val end: String? = null)

/**
 * Cau hinh TIM KIEM cua 1 site (khoi "search" trong parser.json).
 * url: mau dia chi, %s la cho chen tu khoa da encode.
 * item/title/link/author/cover: selector cho tung the ket qua.
 * storyUrlRegex + excludeUrlRegex: dung cho che do du phong theo cau truc link,
 * chay khi selector khong con dung (site doi giao dien).
 */
data class SearchConfig(
    val url: String = "",
    /**
     * Cac dia chi tim kiem DU PHONG (nhieu ten mien cua cung 1 site). Thu lan luot,
     * dia chi nao con song va ra ket qua thi dung. Rat quan trong voi cac site hay
     * doi ten mien nhu truyenfull (.vn/.live/.vision/.io).
     */
    val urls: List<String> = emptyList(),
    /**
     * Mau dung THANG dia chi truyen tu ten khong dau, vd "https://x.com/%d/".
     * Dung lam PHAO CUU SINH khi trang tim kiem doi giao dien hoac chan bot:
     * go dung ten -> van mo duoc truyen.
     */
    val storyUrlTemplates: List<String> = emptyList(),
    val item: FieldSel? = null,
    val title: FieldSel? = null,
    val link: FieldSel? = null,
    val author: FieldSel? = null,
    val cover: FieldSel? = null,
    val storyUrlRegex: String? = null,
    val excludeUrlRegex: String? = null,
    /** Kieu phan hoi cua endpoint tim kiem. Mac dinh la HTML; mot so extension
     * vBook (TruyenCV) goi API JSON thay vi trang web. */
    val responseType: String = "html",
    /**
     * Header rieng khi goi trang tim kiem cua nguon nay (lay tu extension vBook).
     * Vd wikicv doi User-Agent trinh duyet that + Referer/Origin dung ten mien.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Ten truong JSON do site tu khai bao trong khoi "json" cua muc search
     * (chi dung khi responseType = "json"). De null thi SearchEngine dung bo
     * ten truong pho bien (title/name, url/link/href, slug, author, cover...).
     */
    val jsonTitleKey: String? = null,
    val jsonUrlKey: String? = null,
    val jsonSlugKey: String? = null,
    val jsonAuthorKey: String? = null,
    val jsonCoverKey: String? = null,
    /**
     * true = khoi search nay do app TU SINH theo mau chung (chua duoc kiem chung tay).
     * Cac nguon tu sinh duoc tim SAU cac nguon da tinh chinh, de nguon tot chiem bang thong truoc.
     */
    val generated: Boolean = false,
) {
    /** Danh sach dia chi tim kiem theo thu tu uu tien (url chinh truoc, roi den du phong). */
    val allUrls: List<String>
        get() = (listOf(url) + urls).filter { it.isNotBlank() }.distinct()
}

data class SiteConfig(
    val domain: String = "",
    val keys: List<String> = emptyList(),
    val useHtmlParser: Boolean = false,
    val useWebview: Boolean = false,
    val needGecko: Boolean = false,
    val title: FieldSel? = null,          // ten truyen
    val chapter: FieldSel? = null,        // ten chuong
    val content: FieldSel? = null,        // vung noi dung chuong
    val nextButton: FieldSel? = null,     // link CHUONG SAU
    val preButton: FieldSel? = null,      // link CHUONG TRUOC
    val detailFirstChapter: FieldSel? = null,   // tu trang gioi thieu -> link CHUONG DAU
    val detailFirstChapterJs: String? = null,   // vd "click:#readnowbtn" -> nut mo chuong dau
    val contentLoadJs: String? = null,    // JS tiem vao WebView de "ep" noi dung hien ra
    val contentRemoves: List<ContentRemove> = emptyList(),
    val contentReplaces: List<String> = emptyList(),
    val htmlRemoves: List<String> = emptyList(),   // selector cac element bi xoa truoc khi lay text
    val dataLoadingChecker: List<String> = emptyList(), // id element => co mat nghia la NOI DUNG SAN SANG
    val dataReadyChecker: List<String> = emptyList(),   // text/class => con hien nghia la DANG TAI
    val mayNeedVpn: Boolean = false,
    val applyWordSegmentation: Boolean = false,
    val chapterIgnoreDomain: Boolean = false,
    val search: SearchConfig? = null,
) {
    /** Match exact domains/subdomains and complete legacy alias labels. */
    fun matches(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        return (listOf(domain) + keys).any { raw ->
            val candidate = raw.trim().trim('.').lowercase()
            if (candidate.isBlank()) return@any false
            if (candidate.contains('.')) {
                h == candidate || h.endsWith(".$candidate")
            } else {
                // Legacy parser keys can be short aliases (e.g. "wikicv").
                // Accept only a direct lookup key, never a label inside an
                // arbitrary hostname such as wikicv.evil.example.
                h == candidate
            }
        }
    }

    val allDomains: List<String> get() = (listOf(domain) + keys).filter { it.isNotBlank() }.distinct()
}

/**
 * Kho cau hinh — nap parser.json tu assets (1 lan, thread-safe) va tra ve config theo URL.
 */
object SiteConfigStore {
    @Volatile private var configs: List<SiteConfig>? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun ensureLoaded() {
        if (configs != null) return
        synchronized(this) {
            if (configs != null) return
            // Loi doc tam thoi (assets/parse) KHONG duoc dong bang thanh
            // "khong co site nao": giu configs = null de lan ensureLoaded()
            // sau thu lai. Ban cu cache emptyList() vinh vien -> toan bo tim
            // kiem da nguon chet toi khi kill app.
            configs = try { load() } catch (e: Exception) { null }
        }
    }

    /** Reload after a downloaded parser bundle has been activated. */
    fun reload() {
        synchronized(this) {
            configs = null
            ensureLoaded()
        }
    }

    private fun load(): List<SiteConfig> {
        val ctx = Config.appContext ?: return emptyList()
        val downloaded = File(ctx.filesDir, "web_parser/parser.json")
        val text = if (downloaded.isFile) downloaded.readText(Charsets.UTF_8) else
            ctx.assets.open("parser.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = json.parseToJsonElement(text).jsonArray
        return arr.mapNotNull { el -> (el as? JsonObject)?.let { parseConfig(it) } }
    }

    fun findForUrl(url: String): SiteConfig? {
        ensureLoaded()
        val host = try { (URI(url).host ?: "").lowercase() } catch (e: Exception) { "" }
        return configs?.firstOrNull { it.matches(host) }
    }

    fun allDomains(): List<String> {
        ensureLoaded()
        return configs?.flatMap { it.allDomains }?.distinct() ?: emptyList()
    }

    /** Toan bo cau hinh site da nap (dung cho tim kiem tren nhieu nguon). */
    fun allConfigs(): List<SiteConfig> {
        ensureLoaded()
        return configs ?: emptyList()
    }

    // ---- parse helpers ----
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun bool(o: JsonObject, k: String): Boolean = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun strList(e: JsonElement?): List<String> =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun field(e: JsonElement?): FieldSel? {
        val o = e as? JsonObject ?: return null
        val sel = str(o, "selector")
        val v = str(o, "value")
        val idx = (o["element_indexed"] as? JsonPrimitive)?.intOrNull
        val start = str(o, "start")
        val middle = str(o, "middle")
        val end = str(o, "end")
        if (sel == null && v == null && idx == null && start == null && end == null) return null
        return FieldSel(selector = sel, value = v, elementIndexed = idx, start = start, middle = middle, end = end)
    }

    private fun contentRemoves(e: JsonElement?): List<ContentRemove> =
        (e as? JsonArray)?.mapNotNull { item ->
            (item as? JsonObject)?.let { ContentRemove(start = str(it, "start"), end = str(it, "end")) }
        } ?: emptyList()

    private fun htmlRemoves(e: JsonElement?): List<String> =
        (e as? JsonArray)?.mapNotNull { item ->
            when (item) {
                is JsonObject -> str(item, "selector")
                is JsonPrimitive -> item.contentOrNull
                else -> null
            }
        } ?: emptyList()

    /** Doc khoi "search" trong parser.json. Thieu url thi coi nhu site khong ho tro tim kiem. */
    private fun searchCfg(e: JsonElement?): SearchConfig? {
        val o = e as? JsonObject ?: return null
        val urls = strList(o["urls"])
        val storyTemplates = strList(o["story_url_templates"])
            .ifEmpty { listOfNotNull(str(o, "story_url_template")) }
        val url = str(o, "url") ?: urls.firstOrNull().orEmpty()
        // A site with no search endpoint can still support exact-title lookup
        // through its verified story URL templates.
        if (url.isBlank() && urls.isEmpty() && storyTemplates.isEmpty()) return null
        return SearchConfig(
            url = url,
            urls = urls,
            storyUrlTemplates = storyTemplates,
            item = field(o["item"]),
            title = field(o["title"]),
            link = field(o["link"]),
            author = field(o["author"]),
            cover = field(o["cover"]),
            storyUrlRegex = str(o, "story_url_regex"),
            excludeUrlRegex = str(o, "exclude_url_regex"),
            responseType = str(o, "response_type") ?: "html",
            jsonTitleKey = (o["json"] as? JsonObject)?.let { str(it, "title") },
            jsonUrlKey = (o["json"] as? JsonObject)?.let { str(it, "url") },
            jsonSlugKey = (o["json"] as? JsonObject)?.let { str(it, "slug") },
            jsonAuthorKey = (o["json"] as? JsonObject)?.let { str(it, "author") },
            jsonCoverKey = (o["json"] as? JsonObject)?.let { str(it, "cover") },
            headers = (o["headers"] as? JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                ?.toMap() ?: emptyMap(),
            generated = (o["generated"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun parseConfig(o: JsonObject): SiteConfig? {
        val domain = str(o, "domain") ?: str(o, "key") ?: (strList(o["keys"]).firstOrNull() ?: return null)
        val keys = strList(o["keys"]).ifEmpty { listOfNotNull(str(o, "key")) }
        return SiteConfig(
            domain = domain,
            keys = keys,
            useHtmlParser = bool(o, "use_html_parser"),
            useWebview = bool(o, "use_webview"),
            needGecko = bool(o, "need_gecko"),
            title = field(o["title"]),
            chapter = field(o["chapter"]),
            content = field(o["content"]),
            nextButton = field(o["next_button"]),
            preButton = field(o["pre_button"]),
            detailFirstChapter = field(o["detail_first_chapter"]),
            detailFirstChapterJs = str(o, "detail_first_chapter_js"),
            contentLoadJs = str(o, "content_load_js"),
            contentRemoves = contentRemoves(o["content_removes"]),
            contentReplaces = strList(o["content_replaces"]),
            htmlRemoves = htmlRemoves(o["html_removes"]),
            dataLoadingChecker = strList(o["data_loading_checker"]),
            dataReadyChecker = strList(o["data_ready_checker"]),
            mayNeedVpn = bool(o, "may_need_vpn"),
            applyWordSegmentation = bool(o, "apply_word_segmentation"),
            chapterIgnoreDomain = bool(o, "chapter_ignore_domain"),
            search = searchCfg(o["search"]),
        )
    }
}
