package vn.tramtruyen.reader.engine

import vn.tramtruyen.reader.engine.ebooks.EpubParser
import vn.tramtruyen.reader.engine.network.ProxyEndpoint
import vn.tramtruyen.reader.engine.network.ProxyManager
import vn.tramtruyen.reader.engine.sources.BaseSiteAdapter
import vn.tramtruyen.reader.engine.sources.GenericExtractor
import vn.tramtruyen.reader.engine.sources.SearchCache
import vn.tramtruyen.reader.engine.sources.SearchEngine
import vn.tramtruyen.reader.engine.sources.SangTacVietChapter
import vn.tramtruyen.reader.engine.sources.SiteConfigStore
import vn.tramtruyen.reader.engine.sources.SearchHit
import vn.tramtruyen.reader.engine.sources.TitleMatcher
import vn.tramtruyen.reader.engine.sources.PageFetcher
import vn.tramtruyen.reader.engine.sources.WebViewFetcher
import vn.tramtruyen.reader.engine.sources.SiteRegistry
import vn.tramtruyen.reader.engine.sources.StoryMeta
import vn.tramtruyen.reader.engine.speech.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * ReadingEngine — trai tim xu ly cua ung dung, THUAN KOTLIN.
 *
 * Truoc day toan bo logic nay nam sau mot may chu Ktor cuc bo (http://127.0.0.1:8000)
 * va giao dien WebView goi qua fetch()/WebSocket. Nay KHONG con server: moi "endpoint"
 * cu da tro thanh mot ham Kotlin goi truc tiep tu JavaScript Bridge (NativeBridge).
 *
 * - Khong Ktor, khong HTTP, khong localhost, khong port, khong routing.
 * - Module doc lap: chi phu thuoc vao cac lop nghiep vu (scrapers/tts/epub/net/Store...).
 * - Moi ham tra ve chuoi JSON (String) hoac ByteArray (TTS) de Bridge chuyen sang JS.
 * - Loi nghiep vu nem ApiError(status, message); Bridge bat va tra ve JS.
 */
object ReadingEngine {

    /** Cac nguon duoc phep tham gia tim kiem web (cau truc lay tu vBook extensions). */
    private val SEARCH_SOURCE_ALLOWLIST = setOf(
        // Bo tim kiem V106 duoc giu nguyen; Hako la nguon bo sung duy nhat.
        "www.truyenmoikk.com", "truyennet.org", "blhvip.vn", "apptruyenchu.pro",
        "truyendich.space", "wikicv.org", "metruyenchuvn.org", "ln.hako.vn",
    )
    private fun searchConfigs() = SiteConfigStore.allConfigs().filter {
        it.search != null && it.domain.lowercase() in SEARCH_SOURCE_ALLOWLIST
    }

    private val cacheEpoch = java.util.concurrent.atomic.AtomicLong(0)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Cache TTS (LRU) gioi han theo DUNG LUONG RAM thay vi so luong -> tranh giu qua
    // nhieu MP3 lon lam phinh bo nho (clip dai co the vai tram KB). Toi da ~64MB, tu
    // dong xoa cac muc cu nhat (LRU) khi vuot nguong.
    private const val TTS_CACHE_MAX_BYTES = 64L * 1024 * 1024
    private var ttsCacheBytes = 0L
    private val ttsCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)

    private fun ttsCacheGet(key: String): ByteArray? = synchronized(ttsCache) { ttsCache[key] }

    private fun ttsCachePut(key: String, data: ByteArray) {
        synchronized(ttsCache) {
            val old = ttsCache.put(key, data)
            if (old != null) ttsCacheBytes -= old.size
            ttsCacheBytes += data.size
            // Xoa muc cu nhat cho den khi duoi nguong, luon giu it nhat 1 muc.
            val iter = ttsCache.entries.iterator()
            while (ttsCacheBytes > TTS_CACHE_MAX_BYTES && ttsCache.size > 1 && iter.hasNext()) {
                val e = iter.next()
                ttsCacheBytes -= e.value.size
                iter.remove()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Health
    // ---------------------------------------------------------------------
    fun health(): String = buildJsonObject {
        put("ok", true)
        put("webapp_built", true)
    }.toString()

    // ---------------------------------------------------------------------
    // Doc chuong / danh sach site / muc luc (crawl + phan tich website)
    // ---------------------------------------------------------------------
    // Cache LRU noi dung chuong (theo URL): chuyen chuong / doc lai / lui chuong gan nhu
    // tuc thi vi khong phai tai lai tu mang. Chi giu trong RAM, toi da CHAPTER_CACHE_MAX chuong.
    private const val CHAPTER_CACHE_MAX = 10
    private val chapterCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > CHAPTER_CACHE_MAX
    }
    /** Một URL chỉ được tải/phân tích một lần, kể cả khi warm và chuyển chương đụng nhau. */
    private val chapterInFlight = HashMap<String, CompletableDeferred<String>>()
    private const val CHAPTER_HOST_COOLDOWN_MS = 1_200L
    private val chapterHostLocks = ConcurrentHashMap<String, Mutex>()
    private val chapterHostLastRequestMs = ConcurrentHashMap<String, Long>()

    private fun chapterHost(url: String): String =
        runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    private fun looksLikeAccessCheck(html: String): Boolean {
        val head = html.take(32_000)
        val commonMarkers = Regex(
            "(just a moment|verify you are human|checking your browser|security check|cf-challenge|challenge-platform)",
            RegexOption.IGNORE_CASE,
        )
        return commonMarkers.containsMatchIn(head) ||
            (html.length < 20_000 && head.contains("check linh căn", ignoreCase = true))
    }

    suspend fun chapter(url: String?): String {
        val safeUrl = url?.takeIf { it.isNotBlank() }
            ?: throw ApiError(422, "Thieu tham so url")
        val epoch = cacheEpoch.get()
        // Tra ngay tu cache neu da tai truoc do -> chuyen chuong tuc thi.
        synchronized(chapterCache) { chapterCache[safeUrl] }?.let { return it }

        val mine = CompletableDeferred<String>()
        val existing = synchronized(chapterInFlight) {
            val pending = chapterInFlight[safeUrl]
            if (pending == null) chapterInFlight[safeUrl] = mine
            pending
        }
        if (existing != null) return existing.await()

        try {
            val adapter = SiteRegistry.getAdapterForUrl(safeUrl)
                ?: throw ApiError(400, "Khong ho tro site nay. Ho tro: ${SiteRegistry.listSupportedSites()}")
            val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
            val result = try {
                val host = chapterHost(safeUrl)
                val lock = chapterHostLocks.computeIfAbsent(host) { Mutex() }
                val html = lock.withLock {
                    val now = System.nanoTime() / 1_000_000L
                    val last = chapterHostLastRequestMs[host] ?: 0L
                    val waitMs = (CHAPTER_HOST_COOLDOWN_MS - (now - last)).coerceAtLeast(0L)
                    if (waitMs > 0) delay(waitMs)
                    try {
                        PageFetcher.fetch(safeUrl, client).html
                    } finally {
                        chapterHostLastRequestMs[host] = System.nanoTime() / 1_000_000L
                    }
                }
                parseChapter(adapter, safeUrl, html)
            } finally {
                client.close()
            }
            synchronized(chapterCache) {
                if (epoch != cacheEpoch.get()) throw CancellationException("Dữ liệu cache đã bị xoá")
                chapterCache[safeUrl] = result
            }
            mine.complete(result)
            return result
        } catch (e: CancellationException) {
            mine.completeExceptionally(e)
            throw e
        } catch (e: ApiError) {
            mine.completeExceptionally(e)
            throw e
        } catch (e: BlockedException) {
            val wrapped = ApiError(
                429,
                "Nguồn từ chối yêu cầu (HTTP ${e.code}). Ứng dụng đã dừng; hãy mở trang nguồn và tự xác minh, hoặc đọc trực tiếp trên trang đó.",
            )
            mine.completeExceptionally(wrapped)
            throw wrapped
        } catch (e: Exception) {
            val wrapped = ApiError(500, "Loi khi tai chuong: ${e.message}")
            mine.completeExceptionally(wrapped)
            throw wrapped
        } finally {
            synchronized(chapterInFlight) {
                if (chapterInFlight[safeUrl] === mine) chapterInFlight.remove(safeUrl)
            }
        }
    }

    suspend fun chapterFromBrowser(url: String, html: String): String {
        val adapter = SiteRegistry.getAdapterForUrl(url)
            ?: throw ApiError(400, "Nguồn này chưa được hỗ trợ")
        return parseChapter(adapter, url, html, fromBrowser = true)
    }

    private suspend fun parseChapter(
        adapter: BaseSiteAdapter,
        url: String,
        html: String,
        fromBrowser: Boolean = false,
    ): String {
        if (looksLikeAccessCheck(html)) {
            throw ApiError(429, "Trang yêu cầu xác minh truy cập. Hãy mở chương trong trình duyệt sau khi xác minh.")
        }
        val stvChapter = if (SangTacVietChapter.matches(url)) SangTacVietChapter.parse(html, url) else null
        val chapter = stvChapter?.first ?: adapter.getChapter(html, url)
        val content = chapter.content.lineSequence().filterNot { it.startsWith("[ảnh]", ignoreCase = true) }
            .joinToString("\n").trim()
        if (content.none(Char::isLetterOrDigit)) throw ApiError(422, "Nguồn không trả nội dung chương")
        if (fromBrowser && stvChapter == null && !GenericExtractor.looksLikeChapterPage(html, url, chapter)) {
            throw ApiError(422, "Hãy mở trang nội dung của một chương trước khi bấm nghe.")
        }
        val meta = stvChapter?.second ?: adapter.getStoryMeta(html, url)
        val storyTitle = listOf(chapter.storyTitle, meta.title)
            .asSequence()
            .map { GenericExtractor.cleanStoryTitle(it, chapter.title) }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        return buildJsonObject {
            put("title", chapter.title)
            put("content", content)
            put("story_title", storyTitle)
            put("next_url", chapter.nextUrl.orEmpty())
            put("prev_url", chapter.prevUrl.orEmpty())
            put("chapter_url", url)
            put("cover_url", meta.coverUrl.ifBlank { "" })
        }.toString()
    }

    /**
     * TIM TRUYEN TREN TAT CA TRANG NGUON co khai bao khoi "search" trong parser.json.
     *
     * - Chay SONG SONG tren moi nguon (moi nguon co han gio rieng) nen tim ca chuc
     *   trang van nhanh; nguon nao cham/loi chi bi bo qua.
     * - Ket qua duoc CHAM DIEM theo do giong ten (bo dau tieng Viet, chiu go sai
     *   chinh ta), gop trung giua cac nguon roi sap xep: dung ten truoc, gan dung sau.
     */
    suspend fun search(query: String?): String = searchStream(query, null)

    /**
     * TIM KIEM "HIEN NGAY" — ban streaming cua [search].
     *
     * Khac biet duy nhat: moi lan CO THEM mot nguon tra ket qua ve, [onPartial] duoc goi
     * voi JSON y het dinh dang ket qua cuoi (them co `partial = true`). Man hinh nho do
     * hien dong dau tien sau ~0.3-1 giay thay vi doi ca 72 nguon.
     *
     * Ba tang tang toc:
     *  1) TRUNG TU KHOA da tim -> tra ve tu bo nho dem, 0ms, khong cham mang.
     *  2) MO RONG TU KHOA ("tien" -> "tien nghich") -> ban ngay ket qua loc tu lan truoc
     *     qua [onPartial] de co cai nhin lien, roi mang ve sau se ghi de.
     *  3) Ket qua tung nguon duoc day len ngay khi ve, khong cho nguon cham nhat.
     */
    suspend fun searchStream(
        query: String?,
        onPartial: ((String) -> Unit)?,
        /** The loai loc kem (duoc GHEP vao tu khoa gui di, nhung KHONG tinh vao cham diem). */
        genre: String? = null,
        /**
         * Chi tim tren cac domain nay. Rong = tat ca nguon con song.
         * Day la bo loc CUNG: site khong nam trong list se khong bi hoi.
         */
        onlyDomains: List<String> = emptyList(),
    ): String {
        val q = (query ?: "").trim()
        if (q.isBlank()) throw ApiError(422, "Thieu tu khoa tim kiem")
        val all = searchConfigs()

        fun norm(d: String) = d.lowercase().removePrefix("www.")
        val only = onlyDomains.map { norm(it) }.filter { it.isNotBlank() }.toSet()
        val sites = if (only.isEmpty()) {
            all
        } else {
            all.filter { cfg ->
                val d = norm(cfg.domain)
                d in only || cfg.keys.any { norm(it) in only }
            }
        }
        if (sites.isEmpty()) throw ApiError(400, "Không còn nguồn nào khớp lựa chọn")

        val g = genre?.trim().orEmpty()
        val sendTerm = if (g.isBlank()) q else "$q $g"
        val scopeKey = if (only.isEmpty()) "" else " ~src:" + only.sorted().joinToString(",")
        val cacheKey = sendTerm + scopeKey

        SearchCache.get(cacheKey)?.let { cached ->
            return searchJson(q, cached, sites.size, emptyList(), partial = false)
        }

        if (onPartial != null) {
            val guess = SearchCache.guessFromPrefix(q)
            val scoped = if (only.isEmpty()) guess else guess.filter { h ->
                val host = runCatching {
                    java.net.URI(h.url).host?.lowercase()?.removePrefix("www.").orEmpty()
                }.getOrDefault("")
                host.isNotBlank() && (
                    host in only || only.any { host == it || host.endsWith(".$it") }
                )
            }
            if (scoped.isNotEmpty()) {
                onPartial(searchJson(q, scoped, sites.size, emptyList(), partial = true))
            }
        }

        val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val hits: List<SearchHit>
        try {
            hits = SearchEngine.searchAll(
                configs = sites,
                query = sendTerm,
                client = client,
                onSiteFailed = { domain -> failed.add(domain) },
                onPartial = if (onPartial == null) null else { partialHits ->
                    onPartial(searchJson(q, partialHits, sites.size, failed.toList(), partial = true))
                },
                matchQuery = q,
                onlyCurated = false,
                // A source explicitly selected in the picker must not be
                // silently filtered out by failures from earlier searches.
                preferredDomains = if (only.isNotEmpty()) sites.map { it.domain }.toSet() else emptySet(),
            )
        } finally {
            client.close()
        }

        SearchCache.put(cacheKey, hits, complete = failed.isEmpty())
        return searchJson(q, hits, sites.size, failed.toList(), partial = false)
    }

    /**
     * Danh sach web nguon cho UI "Nguon web muon tim".
     * @param all true = moi site co search (mac dinh).
     */
    fun searchSourceDomains(all: Boolean = true): List<String> =
        searchConfigs()
            .filter { all || it.search?.generated != true }
            .map { it.domain }
            .distinct()
            .sorted()

    /** Suggestions from the selected site's actual home page, not local history. */
    suspend fun sourceSuggestions(domain: String): List<SearchHit> {
        val cfg = searchConfigs().firstOrNull { it.domain == domain } ?: return emptyList()
        val search = cfg.search ?: return emptyList()
        val include = search.storyUrlRegex?.let { runCatching { Regex(it) }.getOrNull() }
            ?: return emptyList()
        val exclude = search.excludeUrlRegex?.let { runCatching { Regex(it) }.getOrNull() }
        val home = "https://${cfg.domain}/"
        val html = try {
            HttpClient(minDelay = 0.0, maxDelay = 0.0).fetchFast(
                home, timeoutMs = 7_000L, extraHeaders = search.headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sites such as Wikicv/TruyenDich may present a JS shell or a 403
            // to OkHttp while the same home page renders in an Android WebView.
            WebViewFetcher.fetchRendered(home,
                cfg.copy(content = search.item ?: search.title ?: cfg.content,
                    dataLoadingChecker = emptyList(), dataReadyChecker = emptyList()))
        }
        val doc = org.jsoup.Jsoup.parse(html, home)
        return doc.select("a[href]").asSequence().mapNotNull { anchor ->
            val url = anchor.absUrl("href").substringBefore('#')
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            if (host == null || host.removePrefix("www.") != domain.removePrefix("www.") ||
                !include.containsMatchIn(url) || exclude?.containsMatchIn(url) == true
            ) return@mapNotNull null
            val img = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
            val title = (anchor.attr("title").ifBlank { anchor.text() }
                .ifBlank { img?.attr("alt").orEmpty() }).trim().replace(Regex("\\s+"), " ")
            if (title.length !in 4..120 || title.matches(Regex("(?i)^(đọc truyện|xem thêm|chi tiết|truyện mới).*$"))) {
                return@mapNotNull null
            }
            val cover = img?.let { image ->
                sequenceOf("data-src", "data-original", "data-lazy-src", "src")
                    .map { image.absUrl(it) }.firstOrNull { it.startsWith("https://") }
            }.orEmpty()
            SearchHit(title = title, url = url, coverUrl = cover, source = domain)
        }.distinctBy { it.url.trimEnd('/') }.take(20).toList()
    }

    /** Lam nong san ket noi toi cac nguon chinh (goi luc mo man hinh tim kiem). */
    suspend fun warmUpSearch(): Set<String> {
        val sites = searchConfigs()
        if (sites.isEmpty()) return emptySet()
        val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
        return try { SearchEngine.warmUp(sites, client) } finally { client.close() }
    }

    /** Dong goi ket qua tim kiem thanh JSON (dung chung cho ban tam thoi va ban cuoi). */
    private fun searchJson(
        q: String,
        hits: List<SearchHit>,
        siteCount: Int,
        failed: List<String>,
        partial: Boolean,
    ): String = buildJsonObject {
        put("query", q)
        put("count", hits.size)
        put("partial", partial)
        put("searched_sites", siteCount)
        put("failed_sites", JsonArray(failed.distinct().map { JsonPrimitive(it) }))
        put("results", JsonArray(hits.map { h ->
            buildJsonObject {
                put("title", h.title)
                put("url", h.url)
                put("author", h.author)
                put("cover_url", h.coverUrl)
                put("source", h.source)
                put("score", h.score)
                put("exact", h.exact)
                put("match_label", TitleMatcher.label(h.score))
                put("mirrors", JsonArray(h.mirrors.map { m -> JsonPrimitive(m) }))
            }
        }))
    }.toString()

    /**
     * TU TRANG GIOI THIEU -> CHUONG 1 (de "bam la nghe ngay").
     *
     * Thu lan luot cho den khi ra link chuong dau:
     *  1) Nut "doc tu dau" theo cau hinh site (adapter.getFirstChapterUrl).
     *  2) Muc luc tren chinh trang gioi thieu (chuong dau tien trong danh sach).
     *  3) Trang muc luc rieng (getTocUrl) neu trang gioi thieu khong liet ke chuong.
     *  4) Neu link nguoi dung dua VON DA la mot chuong -> dung luon link do.
     *
     * Tra ve JSON { first_chapter_url, chapter_title, story_title, cover_url, total_chapters }.
     */
    suspend fun firstChapter(url: String?): String {
        if (url.isNullOrBlank()) throw ApiError(422, "Thieu tham so url")
        val adapter = SiteRegistry.getAdapterForUrl(url)
            ?: throw ApiError(400, "Khong ho tro site nay")
        val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
        try {
            val html = PageFetcher.fetch(url, client).html
            val meta = try { adapter.getStoryMeta(html, url) } catch (_: Exception) { StoryMeta() }

            var chapters = try { adapter.getChapterList(html, url) } catch (_: Exception) { emptyList() }
            var first = try { adapter.getFirstChapterUrl(html, url) } catch (_: Exception) { null }
            if (first.isNullOrBlank()) first = chapters.firstOrNull()?.url

            if (first.isNullOrBlank()) {
                val tocUrl = adapter.getTocUrl(url)
                if (!tocUrl.isNullOrBlank() && tocUrl != url) {
                    try {
                        val tocHtml = PageFetcher.fetch(tocUrl, client).html
                        val more = adapter.getChapterList(tocHtml, tocUrl)
                        if (more.isNotEmpty()) {
                            chapters = more
                            first = more.first().url
                        }
                    } catch (_: Exception) {}
                }
            }

            // Link dua vao von da la 1 chuong -> nghe luon chinh no.
            if (first.isNullOrBlank() && looksLikeChapterUrl(url)) first = url

            if (first.isNullOrBlank())
                throw ApiError(404, "Khong tim thay chuong dau cua truyen nay")

            val firstTitle = chapters.firstOrNull { it.url == first }?.title.orEmpty()
            return buildJsonObject {
                put("first_chapter_url", first)
                put("chapter_title", firstTitle)
                put("story_title", meta.title)
                put("author", meta.author)
                put("cover_url", meta.coverUrl)
                put("total_chapters", chapters.size)
                put("story_url", url)
            }.toString()
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(500, "Loi khi mo chuong dau: ${e.message}")
        } finally { client.close() }
    }

    private val CHAPTER_URL_RE = Regex("(chuong|chapter|chap|episode|/c/|-c-|tap)[-_/]?\\d", RegexOption.IGNORE_CASE)

    private fun looksLikeChapterUrl(url: String): Boolean = CHAPTER_URL_RE.containsMatchIn(url)

    fun sites(): String = buildJsonObject {
        put("sites", JsonArray(SiteRegistry.listSupportedSites().map { JsonPrimitive(it) }))
    }.toString()

    suspend fun toc(url: String?): String {
        if (url.isNullOrBlank()) throw ApiError(422, "Thieu tham so url")
        val adapter = SiteRegistry.getAdapterForUrl(url)
            ?: throw ApiError(400, "Khong ho tro site nay")
        val client = HttpClient(minDelay = 0.0, maxDelay = 0.0)
        try {
            val html = PageFetcher.fetch(url, client).html
            var links = adapter.getChapterList(html, url)
            var sourceUrl = url
            if (links.size < 3) {
                val tocUrl = adapter.getTocUrl(url)
                if (!tocUrl.isNullOrBlank() && tocUrl != url) {
                    try {
                        val tocHtml = PageFetcher.fetch(tocUrl, client).html
                        val more = adapter.getChapterList(tocHtml, tocUrl)
                        if (more.size > links.size) { links = more; sourceUrl = tocUrl }
                    } catch (_: Exception) {}
                }
            }
            val meta = try { adapter.getStoryMeta(html, url) } catch (_: Exception) { StoryMeta() }
            return buildJsonObject {
                put("story_title", meta.title)
                put("toc_url", sourceUrl)
                put("count", links.size)
                put("chapters", JsonArray(links.mapIndexed { i, ch ->
                    buildJsonObject {
                        put("index", i + 1)
                        put("title", ch.title)
                        put("url", ch.url)
                    }
                }))
            }.toString()
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(500, "Loi khi lay muc luc: ${e.message}")
        } finally { client.close() }
    }

    // ---------------------------------------------------------------------
    // Quan ly du lieu: lich su + thu vien
    // ---------------------------------------------------------------------
    fun historyGet(): String = buildJsonObject { put("items", JsonArray(Store.getHistory())) }.toString()
    fun historyUpsert(itemJson: String): String {
        val item = json.parseToJsonElement(itemJson).jsonObject
        return buildJsonObject { put("items", JsonArray(Store.upsertHistory(item))) }.toString()
    }
    fun historyClear(): String = buildJsonObject { put("items", JsonArray(Store.clearHistory())) }.toString()
    fun historyRemove(storyId: String?): String =
        buildJsonObject { put("items", JsonArray(Store.removeHistory(storyId ?: ""))) }.toString()

    fun libraryGet(): String = buildJsonObject { put("items", JsonArray(Store.getLibrary())) }.toString()
    fun libraryUpsert(itemJson: String): String {
        val item = json.parseToJsonElement(itemJson).jsonObject
        return buildJsonObject { put("items", JsonArray(Store.upsertLibrary(item))) }.toString()
    }
    fun libraryRemove(storyId: String?): String =
        buildJsonObject { put("items", JsonArray(Store.removeLibrary(storyId ?: ""))) }.toString()

    // ---------------------------------------------------------------------
    // Anh bia
    // ---------------------------------------------------------------------
    fun covers(): String = buildJsonObject {
        // Local-only fallbacks: empty URL list + color seeds (UI paints gradient monogram).
        put("covers", JsonArray(emptyList()))
        put("colors", JsonArray(Covers.FALLBACK_COLORS.map { JsonPrimitive(it) }))
    }.toString()

    // ---------------------------------------------------------------------
    // TTS
    // ---------------------------------------------------------------------
    fun ttsVoices(): String {
        val voices = TtsManager.getAllVoices()
        return buildJsonObject {
            put("default", TtsManager.DEFAULT_VOICE)
            put("total", voices.size)
            put("cloudProviders", JsonArray(TtsManager.cloudProviderKeys().map { JsonPrimitive(it) }))
            put("voices", JsonArray(voices))
        }.toString()
    }

    fun ttsLanguages(): String {
        val voices = TtsManager.getAllVoices()
        val groups = LinkedHashMap<String, MutableList<JsonObject>>()
        for (v in voices) {
            val lang = v["language"]?.jsonPrimitive?.contentOrNull ?: "vi-VN"
            groups.getOrPut(lang) { mutableListOf() }.add(v)
        }
        val langs = groups.map { (code, list) ->
            buildJsonObject {
                put("code", code)
                put("count", list.size)
                put("voices", JsonArray(list.map { v ->
                    buildJsonObject {
                        put("voiceId", v["voice_id"] ?: JsonPrimitive(""))
                        put("name", v["name"] ?: JsonPrimitive(""))
                        put("provider", v["provider"] ?: JsonPrimitive(""))
                    }
                }))
            }
        }
        return buildJsonObject { put("languages", JsonArray(langs)); put("total", voices.size) }.toString()
    }

    fun ttsExport(): String {
        val voices = TtsManager.getAllVoices()
        return buildJsonObject {
            put("default", TtsManager.DEFAULT_VOICE)
            put("total", voices.size)
            put("voices", JsonArray(voices))
        }.toString()
    }

    fun ttsStatus(): String {
        val st = TtsManager.statusMap()
        return buildJsonObject {
            put("android_available", true)
            put("edge_available", true)
            put("gtts_available", true)
            put("capcut_available", true)
            put("tiktok_available", (st["tiktok_available"] as? Boolean) ?: false)
            put("cloud_providers", JsonArray(TtsManager.cloudProviderKeys().map { JsonPrimitive(it) }))
            put("total_voices", (st["total_voices"] as? Int) ?: 0)
            put("default_voice", TtsManager.DEFAULT_VOICE)
        }.toString()
    }

    fun ttsProviders(): String = buildJsonObject {
        putJsonObject("providers") {
            for (p in TtsManager.providerInfo()) {
                putJsonObject(p.key) {
                    put("name", p.name)
                    put("configured", p.configured)
                    put("free", p.free)
                    put("voices", p.voices)
                }
            }
        }
    }.toString()

    /** Tong hop giong noi -> tra ve du lieu am thanh MP3 (ByteArray). Nem ApiError khi loi. */
    fun clearStoryCaches() {
        cacheEpoch.incrementAndGet()
        synchronized(chapterCache) { chapterCache.clear() }
        synchronized(chapterInFlight) {
            chapterInFlight.values.forEach { it.cancel() }
            chapterInFlight.clear()
        }
        synchronized(ttsCache) { ttsCache.clear(); ttsCacheBytes = 0L }
        SearchCache.clear()
    }

    suspend fun synthesize(argsJson: String): ByteArray {
        val epoch = cacheEpoch.get()
        val body = try { json.parseToJsonElement(argsJson).jsonObject }
            catch (e: Exception) { throw ApiError(400, "Body khong hop le") }
        val rawText = body["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        if (rawText.isEmpty()) throw ApiError(400, "Thieu noi dung text")
        if (rawText.length > 6000) throw ApiError(400, "Van ban qua dai (toi da 6000 ky tu)")
        val doNormalize = body["normalize"]?.jsonPrimitive?.booleanOrNull ?: true
        val requestedVoiceId = body["voice_id"]?.jsonPrimitive?.contentOrNull
            ?: body["voiceId"]?.jsonPrimitive?.contentOrNull
            ?: TtsManager.DEFAULT_VOICE
        val voiceId = vn.tramtruyen.reader.engine.speech.TtsVoicePolicy.migrate(requestedVoiceId)
        val text = if (doNormalize) {
            try { TextPipeline.normalizeForTts(rawText, voiceId) } catch (_: Exception) { rawText }
        } else rawText
        val speed = (body["speed"]?.jsonPrimitive?.doubleOrNull ?: 1.0).coerceIn(0.5, 5.0)
        val pitch = (body["pitch"]?.jsonPrimitive?.doubleOrNull ?: 1.0).coerceIn(0.5, 2.0)

        val cacheKey = "$voiceId|${"%.2f".format(speed)}|${"%.2f".format(pitch)}|$text"
        // Preserve the provider-specific cache and retry policy.
        val cacheable = !voiceId.startsWith("capcut:", ignoreCase = true)
        var audio: ByteArray? = if (cacheable) ttsCacheGet(cacheKey) else null
        if (audio == null) {
            val maxAttempts = if (voiceId.startsWith("capcut:") || voiceId.startsWith("tiktok:")) 1 else 3
            for (attempt in 0 until maxAttempts) {
                audio = try {
                    TtsManager.synthesize(text, voiceId, speed, pitch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: ApiError) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (audio != null) break
                if (attempt + 1 < maxAttempts) kotlinx.coroutines.delay(250L * (attempt + 1))
            }
            if (audio != null && cacheable) synchronized(ttsCache) {
                if (epoch != cacheEpoch.get()) throw CancellationException("Dữ liệu âm thanh đã bị xoá")
                ttsCachePut(cacheKey, audio!!)
            }
        }
        if (audio == null) {
            val detail = TtsManager.lastError?.takeIf { it.isNotBlank() }
            throw ApiError(
                500,
                detail ?: "TTS thất bại. Kiểm tra mạng hoặc API key của provider đang chọn."
            )
        }
        return audio
    }

    // ---------------------------------------------------------------------
    // EPUB
    // ---------------------------------------------------------------------
    fun epubParse(bytes: ByteArray, fileName: String?): String {
        try {
            return EpubParser.parse(bytes, fileName ?: "truyen.epub").toString()
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(500, "Loi phan tich EPUB: ${e.message}")
        }
    }

    /** Parse from a temporary file so the UI never keeps a second 100+ MB copy. */
    fun epubParseFile(file: File, fileName: String?): String {
        if (!file.isFile) throw ApiError(400, "Không mở được EPUB")
        if (file.length() > 200L * 1024L * 1024L) throw ApiError(400, "EPUB vượt giới hạn an toàn 200 MB")
        return try {
            EpubParser.parse(file, fileName ?: file.name, includeFirstContent = false).toString()
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(500, "Lỗi phân tích EPUB: ${e.message}")
        }
    }

    fun epubChapter(id: String?, index: Int): String {
        if (id.isNullOrBlank()) throw ApiError(422, "Thieu tham so id")
        try {
            return EpubParser.chapter(id, index).toString()
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            throw ApiError(500, "Loi doc chuong EPUB: ${e.message}")
        }
    }
    fun epubInfo(id:String?):String{if(id.isNullOrBlank())throw ApiError(422,"Thiếu id");return EpubParser.info(id).toString()}
    fun epubDelete(id:String?):String{if(id.isNullOrBlank())throw ApiError(422,"Thiếu id");return buildJsonObject{put("deleted",EpubParser.delete(id));put("id",id)}.toString()}
}
