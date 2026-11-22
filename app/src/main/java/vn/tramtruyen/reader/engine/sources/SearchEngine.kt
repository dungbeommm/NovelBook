package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** 1 ket qua tim kiem tra ve tu mot trang nguon. */
data class SearchHit(
    val title: String,
    val url: String,
    val author: String = "",
    val coverUrl: String = "",
    val source: String = "",
    /** Diem giong nhau 0..1 so voi tu khoa nguoi dung go. */
    val score: Double = 0.0,
    /** Trung khop tuyet doi ten truyen (sau khi bo dau). */
    val exact: Boolean = false,
    /** Cac nguon khac cung co bo truyen nay (de nguoi dung doi nguon khi 1 nguon loi). */
    val mirrors: List<String> = emptyList(),
)

/**
 * BO TIM KIEM TREN WEB NGUON.
 *
 * Dieu khien hoan toan bang parser.json (khoi "search") nen khi site doi giao dien
 * chi can sua JSON, khong phai bien dich lai app.
 *
 * Hai lop trich xuat:
 *  1) Theo selector khai bao trong cau hinh.
 *  2) Du phong theo CAU TRUC LINK: gom moi the <a> co dia chi khop story_url_regex
 *     va khong khop exclude_url_regex. Lop nay song sot duoc khi site doi ten class.
 *
 * Tim TREN NHIEU NGUON CUNG LUC (searchAll): moi site chay song song, co han gio
 * rieng, site nao cham/loi thi bo qua chu khong keo ca lan tim xuong.
 */
object SearchEngine {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /** Han gio cho MOI request cua 1 site (dat o tang OkHttp nen socket that su bi dut). */
    const val PER_SITE_TIMEOUT_MS = 7_000L

    /** Han gio tong khi quet SAU (T2 / deep). */
    const val GLOBAL_TIMEOUT_MS = 12_000L

    /** Han gio tong tang T1 (chi nguon chinh) — cat som de cam giac "go la thay". */
    const val CORE_TIMEOUT_MS = 6_000L

    const val MAX_PARALLEL_SITES = 14
    const val MAX_PARALLEL_FAST = 2
    const val MAX_PARALLEL_HEAVY = 1

    /** Tong thoi gian toi da cho 1 nguon (gom ca cac dia chi du phong cua nguon do). */
    const val PER_SITE_BUDGET_MS = 11_000L

    /**
     * Nguon phai mo WebView (wikicv va mot so site SPA) can nhieu thoi gian hon:
     * khoi dong WebView + chay JS + vuot thu thach chong bot thuong qua 7s.
     */
    const val WEBVIEW_TIMEOUT_MS = 15_000L
    const val WEBVIEW_BUDGET_MS = 26_000L

    /** Dung som khi da co du ket qua TRUNG TEN. */
    const val EARLY_EXIT_EXACT_HITS = 2

    /** Dung som khi da co du ket qua RAT GAN (score cao), du chua exact. */
    const val EARLY_EXIT_STRONG_HITS = 5
    const val EARLY_EXIT_STRONG_SCORE = 0.80

    /**
     * TIM TREN TAT CA CAC SITE co khai bao "search", chay SONG SONG.
     *
     * @return danh sach da cham diem, da gop trung va sap xep: dung ten truoc,
     *         gan dung sau.
     */
    suspend fun searchAll(
        configs: List<SiteConfig>,
        query: String,
        client: HttpClient,
        onSiteFailed: ((String) -> Unit)? = null,
        /**
         * Duoc goi MOI LAN co them mot nguon tra ket qua ve, kem danh sach da xep hang
         * tinh den thoi diem do. Nho callback nay man hinh hien ket qua dau tien sau
         * ~0.3-1 giay thay vi doi du 72 nguon.
         */
        onPartial: ((List<SearchHit>) -> Unit)? = null,
        /**
         * Tu khoa dung de CHAM DIEM, khi khac voi tu khoa GUI DI cho trang nguon.
         * Vi du loc theo the loai: gui di "tien nghich tien hiep" nhung van cham diem
         * theo "tien nghich", neu khong moi ket qua deu bi tru diem oan.
         */
        matchQuery: String = query,
        /** Chi quet cac nguon da tinh chinh tay (nhanh hon nhieu, bo qua nguon tu sinh). */
        onlyCurated: Boolean = false,
        /**
         * Cac ten mien nguoi dung CHON UU TIEN. Chung duoc ban di TRUOC TIEN, nen
         * ket qua tu nguon quen thuoc hien len dau tien va thuong thang luon vi
         * co che dung som.
         */
        preferredDomains: Set<String> = emptySet(),
    ): List<SearchHit> = supervisorScope {
        val q = query.trim()
        if (q.isBlank()) return@supervisorScope emptyList()
        // Nguon da tinh chinh tay di truoc, nguon tu sinh theo mau di sau.
        val mq = matchQuery.trim().ifBlank { q }
        val pref = preferredDomains.map { it.lowercase().removePrefix("www.") }.toSet()
        // Thu tu ban request: nguon UU TIEN -> nguon tinh chinh tay -> nguon tu sinh.
        fun dom(cfg: SiteConfig) = cfg.domain.lowercase().removePrefix("www.")
        fun isPreferred(cfg: SiteConfig) = pref.isNotEmpty() && pref.contains(dom(cfg))

        // Thu tu ban request:
        //   1) Nhom: nguon UU TIEN -> nguon tinh chinh tay -> nguon tu sinh.
        //   2) Trong cung nhom: nguon NHANH VA ON DINH truoc, theo so lieu da ghi lai.
        // Nguon dang bi cho nghi vi hong lien tiep thi bo han, tru khi chinh nguoi dung
        // chon no lam nguon uu tien.
        fun overridePrefer(cfg: SiteConfig) = SiteOverrides.isPreferred(cfg.domain)
        fun forced(cfg: SiteConfig) = SiteOverrides.isForced(cfg.domain)
        fun dead(cfg: SiteConfig) = SiteOverrides.isDead(cfg.domain)
        val requiredDomains = setOf(
            "truyennet.org", "truyenmoikk.com",
        )
        fun required(cfg: SiteConfig) = dom(cfg) in requiredDomains

        val sites = configs.filter { it.search != null }
            .filter { !onlyCurated || it.search?.generated != true }
            // dead override: bo, tru khi user chon uu tien domain do.
            .filter { isPreferred(it) || !dead(it) }
            // mute / soft-ban: bo, tru uu tien user hoac force.
            .filter {
                required(it) || isPreferred(it) || forced(it) || overridePrefer(it) || SiteStats.isHealthy(it.domain)
            }
            .sortedWith(
                compareBy(
                    { cfg ->
                        when {
                            isPreferred(cfg) || overridePrefer(cfg) -> 0
                            // Nguon bat buoc phai duoc BAN DI SOM, neu khong SiteStats
                            // se day chung (WebView, avgMs cao) xuong cuoi hang doi.
                            required(cfg) -> 1
                            cfg.search?.generated != true -> 2
                            else -> 3
                        }
                    },
                    { cfg -> SiteStats.rankKey(cfg.domain) },
                )
            )
        if (sites.isEmpty()) return@supervisorScope emptyList()
        // Hai hang doi tach biet: nguon nhe va nguon phai mo WebView.
        val fastGate = Semaphore(MAX_PARALLEL_FAST)
        val heavyGate = Semaphore(MAX_PARALLEL_HEAVY)
        // Bat len khi da du ket qua trung ten -> cac nguon chua chay se bo qua luon.
        val enough = java.util.concurrent.atomic.AtomicBoolean(false)

        // Ket qua duoc DO DAN vao day. Site nao xong truoc thi co mat truoc, nen khi
        // het gio tong ta van dung duoc phan da lay ve (khong mat trang).
        val collected = java.util.Collections.synchronizedList(ArrayList<SearchHit>())

        val jobs = sites.map { cfg ->
            launch(Dispatchers.IO) {
                // vBook `fetch()` la HTTP request, KHONG phai WebView. TruyenCV con
                // dung API JSON nen cung phai vao hang nhanh.
                val gate = if (cfg.useWebview && cfg.search?.responseType == "html") heavyGate else fastGate
                gate.withPermit {
                    // Da du ket qua chac chan roi thi khong ban them request nao nua.
                    // NGOAI TRU nguon bat buoc: chung dung WebView nen luon ve cham,
                    // neu cat theo co "enough" thi khong bao gio den luot chung chay.
                    if (enough.get() && !required(cfg)) return@withPermit
                    val startedAt = System.currentTimeMillis()
                    try {
                        val outcome = searchSite(cfg, q, client)
                        val hits = outcome.hits
                        // Job da bi huy trong luc ket qua dang tren duong ve
                        // (fetchFast blocking khong tu dung theo coroutine):
                        // ket qua tre TUYET DOI khong cham vao UI nua.
                        if (!isActive) return@withPermit
                        if (hits.isEmpty()) {
                            // 0 ket qua KHONG phai loi neu site tra loi khoe manh
                            // (chi la khong co truyen nay): khong ha suc khoe nguon,
                            // neu khong cang tim truyen hiem kho nguon cang teo.
                            if (outcome.fetchOk) {
                                SiteStats.recordSuccess(cfg.domain, System.currentTimeMillis() - startedAt)
                            } else {
                                SiteStats.recordFailure(cfg.domain)
                                onSiteFailed?.invoke(cfg.domain)
                            }
                        } else {
                            SiteStats.recordSuccess(cfg.domain, System.currentTimeMillis() - startedAt)
                            collected.addAll(hits)
                            val snapshot = synchronized(collected) { ArrayList(collected) }
                            val ranked = rank(mq, snapshot)
                            // DAY NGAY len giao dien: khong cho cac nguon con lai.
                            onPartial?.invoke(ranked)
                            val exactN = ranked.count { it.exact }
                            val strongN = ranked.count { it.score >= EARLY_EXIT_STRONG_SCORE }
                            if (exactN >= EARLY_EXIT_EXACT_HITS ||
                                strongN >= EARLY_EXIT_STRONG_HITS
                            ) {
                                enough.set(true)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SiteStats.recordFailure(cfg.domain)
                        onSiteFailed?.invoke(cfg.domain)
                    }
                }
            }
        }

        // T1 (onlyCurated) cat som hon; T2 dung han tong day du.
        // Thoat ngay khi enough=true (exact/strong du).
        val waiter = launch { jobs.joinAll() }
        // A single user-selected WebView source needs its full render budget;
        // the multi-source global timeout is too short for such sites.
        val preferredWebView = sites.any { isPreferred(it) && it.useWebview }
        val budget = if ((sites.size == 1 && sites.first().useWebview) || preferredWebView) {
            WEBVIEW_BUDGET_MS
        } else if (onlyCurated) CORE_TIMEOUT_MS else GLOBAL_TIMEOUT_MS
        // Khong thoat khoi vong doi chi vi mot nguon nhanh da bat `enough`.
        // Cac job chua bat dau se tu bo qua, con cac nguon bat buoc dang chay phai
        // duoc phep tra ket qua. Ban cu thoat ngay roi huy tat ca job cham.
        withTimeoutOrNull(budget) { waiter.join() }
        waiter.cancel()
        jobs.forEach { if (it.isActive) it.cancel() }

        // Ghi so lieu suc khoe nguon xuong dia de lan tim sau xep hang thong minh hon.
        SiteStats.flush()

        val raw = synchronized(collected) { ArrayList(collected) }
        rank(mq, raw)
    }

    /**
     * CHAM DIEM + GOP TRUNG + SAP XEP.
     *
     * - Bo cac ket qua qua lech so voi tu khoa (duoi TitleMatcher.MIN_SCORE).
     * - Cung 1 bo truyen xuat hien o nhieu nguon -> giu ban diem cao nhat, cac
     *   nguon con lai tro thanh "mirrors" de doi nguon khi can.
     */
    fun rank(query: String, hits: List<SearchHit>): List<SearchHit> {
        val scored = hits.asSequence()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            // Banner nha cai / phim nguoi lon chen vao danh sach -> bo thang.
            .filter { !AdFilter.isAd(it.title, it.url) }
            // Cung mot dia chi co the ve nhieu lan (nguon co nhieu ten mien du phong).
            .distinctBy { it.url.trimEnd('/') }
            .map { h ->
                val sc = TitleMatcher.score(query, h.title)
                h.copy(score = sc, exact = TitleMatcher.isExact(query, h.title))
            }
            .filter { it.score >= TitleMatcher.MIN_SCORE }
            .toList()

        // Gop theo ten da chuan hoa (cung ten = cung bo truyen o nhieu nguon).
        val grouped = LinkedHashMap<String, MutableList<SearchHit>>()
        for (h in scored) {
            val key = TitleMatcher.compact(h.title).ifBlank { h.url }
            grouped.getOrPut(key) { mutableListOf() }.add(h)
        }

        val merged = grouped.values.map { group ->
            val sorted = group.sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenByDescending { it.coverUrl.isNotBlank() }
                    .thenBy { it.title.length }
            )
            val best = sorted.first()
            best.copy(
                author = sorted.firstOrNull { it.author.isNotBlank() }?.author ?: best.author,
                coverUrl = sorted.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: best.coverUrl,
                mirrors = sorted.drop(1).map { it.url }.distinct().take(5),
            )
        }

        return merged.sortedWith(
            compareByDescending<SearchHit> { it.exact }
                .thenByDescending { it.score }
                .thenByDescending { it.mirrors.size }
                .thenByDescending { it.coverUrl.isNotBlank() }
                .thenBy { it.title.length }
        ).take(60)
    }

    /**
     * LAM NONG KET NOI truoc khi nguoi dung bam tim.
     *
     * Phan lon do tre cua lan tim dau tien khong phai la tai trang, ma la tra cuu DNS +
     * bat tay TLS cho tung ten mien (thuong 300-800ms moi ten mien). Goi ham nay luc mo
     * man hinh tim kiem de OkHttp mo san ket noi va giu trong pool; luc go tu khoa that
     * thi chi con mot vong request.
     *
     * Chay am tham, moi loi deu bo qua.
     */
    suspend fun warmUp(configs: List<SiteConfig>, client: HttpClient, topN: Int = 10): Set<String> {
        val failed = java.util.Collections.synchronizedSet(HashSet<String>())
        supervisorScope {
            configs.asSequence()
                .filter { it.search != null && it.search?.generated != true }
                .take(topN)
                .forEach { cfg ->
                    launch(Dispatchers.IO) {
                        try {
                            val host = cfg.search?.allUrls?.firstOrNull()
                                ?.substringBefore("/tim-kiem")
                                ?.substringBefore("/?")
                                ?: return@launch
                            client.fetchFast(host, 3_000L)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed.add(cfg.domain)
                        }
                    }
                }
        }
        return failed.toSet()
    }

    /**
     * TIM TREN 1 SITE.
     *
     * - Thu LAN LUOT tung dia chi tim kiem khai bao trong `urls` (mot so site doi ten mien
     *   lien tuc: truyenfull .vn/.live/.vision...). Dia chi nao ra ket qua thi dung lai.
     * - Dung `fetchFast` (1 lan, co han gio that) thay vi PageFetcher de KHONG bi keo dai
     *   boi retry + fallback WebView.
     */
    /** Ket qua tim tren 1 site: hits + site co tra loi KHOE MANH khong (fetchOk). */
    data class SiteOutcome(val hits: List<SearchHit>, val fetchOk: Boolean)

    suspend fun searchSite(cfg: SiteConfig, query: String, client: HttpClient): SiteOutcome {
        val sc = cfg.search ?: return SiteOutcome(emptyList(), fetchOk = false)
        if (query.isBlank()) return SiteOutcome(emptyList(), fetchOk = false)

        val budget = if (cfg.useWebview) WEBVIEW_BUDGET_MS else PER_SITE_BUDGET_MS
        val deadline = System.currentTimeMillis() + budget
        // Co it nhat 1 dia chi tra ve trang that (khong rong, khong shell) chua.
        var sawHealthyPage = false

        for (template in sc.allUrls) {
            if (System.currentTimeMillis() > deadline) break
            val url = buildUrl(template, query)
            val html = try {
                fetchSearchHtml(url, cfg, client)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // ten mien chet / bi chan -> thu dia chi ke tiep
            }
            if (html.isBlank()) continue
            // Endpoint JSON (response_type = "json", vd API TruyenCV): parse JSON
            // thang, khong qua Jsoup va khong qua bo loc shell HTML.
            if (sc.responseType.equals("json", ignoreCase = true)) {
                val jsonHits = extractFromJson(html, cfg, sc, url)
                if (jsonHits != null) {
                    sawHealthyPage = true
                    if (jsonHits.isNotEmpty()) return SiteOutcome(jsonHits.take(40), fetchOk = true)
                }
                continue
            }
            // Trang yeu cau dang nhap / shell rong -> coi nhu fail, thu URL ke.
            if (looksLikeEmptySearchShell(html)) {
                // Chi ha co dang nhap WikiCV khi thay TUONG DANG NHAP THAT (form
                // co o password). Trang thach chong bot / shell tam thoi khong
                // duoc xoa phien dang nhap van con tot cua nguoi dung.
                if (cfg.domain.equals("wikicv.org", ignoreCase = true) && hasLoginForm(html)) {
                    WikiCvSession.setLoggedIn(false)
                }
                continue
            }
            sawHealthyPage = true
            val doc = Jsoup.parse(html, url)
            val bySelector = extractBySelector(doc, cfg, sc)
            val byShape = if (bySelector.size >= 2) emptyList() else extractByLinkShape(doc, cfg, sc)
            val hits = (if (bySelector.size >= 2) bySelector else (bySelector + byShape))
                .filter { it.title.isNotBlank() && it.url.isNotBlank() }
                .distinctBy { it.url }
                .take(40)
            if (hits.isNotEmpty()) {
                if (cfg.domain.equals("wikicv.org", ignoreCase = true)) {
                    WikiCvSession.setLoggedIn(true)
                }
                return SiteOutcome(hits, fetchOk = true)
            }
        }

        // Khong site nao chiu tra ket qua -> DOAN THANG DIA CHI TRUYEN tu ten (slug).
        // Vi du "Tiên Nghịch" -> https://.../tien-nghich/ . Chi nhan khi trang ton tai
        // va tieu de trang khop voi tu khoa. Dung chung deadline voi vong URL o tren
        // (ngan sach con lai), khong tu cap ngan sach moi.
        val guessed = guessBySlug(cfg, sc, query, client, deadline)
        return SiteOutcome(guessed, fetchOk = sawHealthyPage || guessed.isNotEmpty())
    }

    /**
     * Lay HTML trang TIM KIEM.
     * - Site use_webview: uu tien WebView (JS) roi moi OkHttp nhanh.
     * - Site thuong: fetchFast; neu bi chan 403/429/503 thi moi mo WebView.
     * Nhu vay wikicv/site SPA van ra ket qua, con site HTML thuan van nhanh.
     */
    private suspend fun fetchSearchHtml(url: String, cfg: SiteConfig, client: HttpClient): String {
        // HTTP first is much faster for TruyenNet/TruyenFull. If the response is a JS shell
        // or anti-bot page, render it. The copied config waits for SEARCH rows instead of
        // the chapter-content selector (the old code waited ~20s and always timed out).
        val searchRenderCfg = cfg.copy(
            content = cfg.search?.item ?: cfg.search?.title ?: cfg.content,
            dataLoadingChecker = emptyList(),
            dataReadyChecker = emptyList(),
        )
        // Phiên đăng nhập WikiCV nằm trong CookieManager của WebView, không nằm
        // trong cookie jar OkHttp. Dùng thẳng WebView để không nhận login wall
        // và không lãng phí timeout HTTP trước khi đọc kết quả thật.
        if (cfg.domain.equals("wikicv.org", ignoreCase = true)) {
            return WebViewFetcher.fetchRendered(url, searchRenderCfg, useProxy = false)
        }
        val timeout = if (cfg.useWebview) WEBVIEW_TIMEOUT_MS else PER_SITE_TIMEOUT_MS
        try {
            val html = client.fetchFast(url, timeout, cfg.search?.headers.orEmpty())
            if (html.isNotBlank() && !looksLikeEmptySearchShell(html)) return html
            if (!cfg.useWebview) return html
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
            val blocked = e is vn.tramtruyen.reader.engine.BlockedException ||
                msg.contains("403") || msg.contains("429") || msg.contains("503") ||
                msg.contains("timeout", true)
            if (!cfg.useWebview && !blocked) throw e
        }
        return WebViewFetcher.fetchRendered(url, searchRenderCfg, useProxy = false)
    }

    private val PASSWORD_INPUT_RE =
        Regex("""<input[^>]+type\s*=\s*["']?password""", RegexOption.IGNORE_CASE)
    private val LOGIN_FORM_RE =
        Regex("""<form[^>]+action\s*=\s*["'][^"']*(login|dang-nhap|sign-in)""", RegexOption.IGNORE_CASE)

    /** Trang co FORM dang nhap THAT khong (o password hoac form action login).
     * Khac voi chu "dang nhap" o header — gan nhu moi trang deu co chu do. */
    private fun hasLoginForm(html: String): Boolean =
        PASSWORD_INPUT_RE.containsMatchIn(html) || LOGIN_FORM_RE.containsMatchIn(html)

    /** HTML chi co shell / bat dang nhap, khong co link truyen that. */
    private fun looksLikeEmptySearchShell(html: String): Boolean {
        val low = html.lowercase()
        if (low.length < 400) return true
        val hasStoryLink = low.contains("/truyen/") || low.contains("story-") ||
            low.contains("book-item") || low.contains("truyen-list") || low.contains("list-truyen")
        if (hasStoryLink) return false
        // Cac tin hieu trang rong / login wall pho bien o wiki dich.
        return low.contains("đăng nhập để xem") ||
            low.contains("dang nhap de xem") ||
            low.contains("please login") ||
            low.contains("sign in to view") ||
            // Chi coi la login wall khi co FORM dang nhap that. Ban cu chi can
            // thay chu "đăng nhập" (header trang nao cung co) + HTML ngan la
            // loai ca trang ket qua hop le cua cac site nhe.
            (low.length < 8000 && hasLoginForm(html))
    }

    /**
     * DU PHONG CUOI: dung mau `story_url_template` de dung thang dia chi truyen tu ten,
     * roi tai thu. Nho vay go DUNG TEN van ra ket qua ke ca khi trang tim kiem doi giao dien.
     */
    private suspend fun guessBySlug(
        cfg: SiteConfig,
        sc: SearchConfig,
        query: String,
        client: HttpClient,
        deadline: Long,
    ): List<SearchHit> {
        val templates = sc.storyUrlTemplates
        if (templates.isEmpty()) return emptyList()
        val slug = TitleMatcher.normalize(query).replace(" ", "-")
        if (slug.length < 2) return emptyList()
        for (t in templates) {
            if (System.currentTimeMillis() > deadline) return emptyList()
            val url = t.replace("%d", slug).replace("%s", slug).replace("%S", slug)
            val html = try {
                fetchSearchHtml(url, cfg, client)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (html.isBlank()) continue
            val doc = Jsoup.parse(html, url)
            val title = cleanTitle(
                doc.selectFirst("h1")?.text().orEmpty()
                    .ifBlank { doc.title() }
            )
            if (title.isBlank()) continue
            if (TitleMatcher.score(query, title) < TitleMatcher.MIN_SCORE) continue
            val cover = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            return listOf(SearchHit(title = title, url = url, coverUrl = cover, source = cfg.domain))
        }
        return emptyList()
    }

    /**
     * Chen tu khoa vao mau dia chi.
     * Ho tro: %s (co dau, da encode), %S (KHONG DAU, da encode - cho site tim theo slug),
     * va %d (khong dau, noi bang dau gach ngang).
     */
    fun buildUrl(template: String, query: String): String {
        val trimmed = query.trim()
        val q = enc(trimmed)
        val plain = TitleMatcher.normalize(trimmed)
        var out = template
        var substituted = false
        if (out.contains("%S")) { out = out.replace("%S", enc(plain)); substituted = true }
        if (out.contains("%d")) { out = out.replace("%d", enc(plain.replace(" ", "-"))); substituted = true }
        if (out.contains("%s")) { out = out.replace("%s", q); substituted = true }
        // CHI cong query vao duoi khi template khong co placeholder nao.
        // Ban cu cong ca khi %S/%d da thay xong -> URL thanh
        // ".../tien-nghich/ti%C3%AAn+ngh%E1%BB%8Bch" va site tra 404.
        return if (substituted) out else out + q
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- lop 1: theo selector ----------
    private fun extractBySelector(doc: Document, cfg: SiteConfig, sc: SearchConfig): List<SearchHit> {
        val itemSel = sc.item?.selector ?: return emptyList()
        val rows = try { doc.select(itemSel) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<SearchHit>()
        for (row in rows) {
            val link = attrOf(row, sc.link, "href") ?: continue
            if (!isStoryUrl(link, sc)) continue
            val title = textOf(row, sc.title).ifBlank { titleAttr(row, sc) }
            if (title.isBlank()) continue
            out.add(
                SearchHit(
                    title = cleanTitle(title),
                    url = link,
                    author = textOf(row, sc.author),
                    coverUrl = coverOf(row, sc),
                    source = cfg.domain,
                )
            )
        }
        return out
    }

    // ---------- lop 2: du phong theo cau truc link ----------
    private fun extractByLinkShape(doc: Document, cfg: SiteConfig, sc: SearchConfig): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (!isStoryUrl(href, sc)) continue
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (title.length < 2) continue
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            out.add(
                SearchHit(
                    title = cleanTitle(title),
                    url = href,
                    coverUrl = img?.let { imgSrc(it) } ?: "",
                    source = cfg.domain,
                )
            )
        }
        return out
    }

    // ---------- lop JSON: endpoint tim kiem tra JSON (response_type = "json") ----------

    /**
     * Parse phan hoi JSON cua API tim kiem. Di khap cay JSON tim cac object co
     * truong giong ten truyen + duong dan (hoac slug) roi dung SearchHit.
     * Tra ve null neu body khong phai JSON hop le (caller biet fetch chua khoe).
     * Ten truong uu tien theo khai bao "json" trong cau hinh neu co.
     */
    private fun extractFromJson(body: String, cfg: SiteConfig, sc: SearchConfig, baseUrl: String): List<SearchHit>? {
        val root = try { jsonParser.parseToJsonElement(body) } catch (e: Exception) { return null }
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()
        fun walk(el: JsonElement, depth: Int) {
            if (depth > 6 || out.size >= 40) return
            when (el) {
                is JsonArray -> for (child in el) walk(child, depth + 1)
                is JsonObject -> {
                    val hit = jsonObjectToHit(el, cfg, sc, baseUrl)
                    if (hit != null) {
                        if (seen.add(hit.url)) out.add(hit)
                    } else {
                        for (child in el.values) walk(child, depth + 1)
                    }
                }
                else -> Unit
            }
        }
        walk(root, 0)
        return out
    }

    private fun jsonObjectToHit(o: JsonObject, cfg: SiteConfig, sc: SearchConfig, baseUrl: String): SearchHit? {
        fun strOf(vararg keys: String?): String {
            for (k in keys) {
                if (k.isNullOrBlank()) continue
                val v = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (v.isNotBlank()) return v
            }
            return ""
        }
        val title = strOf(sc.jsonTitleKey, "title", "name", "story_name", "storyName", "book_name", "novel_title")
        if (title.isBlank()) return null
        var link = strOf(sc.jsonUrlKey, "url", "link", "href", "story_url", "permalink")
        if (link.isBlank()) {
            val slug = strOf(sc.jsonSlugKey, "slug")
            if (slug.isBlank()) return null
            link = resolveSlugUrl(slug, sc, baseUrl)
        } else {
            link = absUrl(baseUrl, link)
        }
        if (link.isBlank()) return null
        val cover = strOf(sc.jsonCoverKey, "cover", "cover_url", "image", "thumbnail", "poster")
        return SearchHit(
            title = cleanTitle(title),
            url = link,
            author = strOf(sc.jsonAuthorKey, "author", "author_name", "tac_gia"),
            coverUrl = if (cover.isBlank()) "" else absUrl(baseUrl, cover),
            source = cfg.domain,
        )
    }

    /** Dung URL truyen tu slug: uu tien story_url_template cua site, roi den goc domain. */
    private fun resolveSlugUrl(slug: String, sc: SearchConfig, baseUrl: String): String {
        val s = slug.trim().trim('/')
        if (s.isBlank()) return ""
        if (s.startsWith("http")) return s
        val template = sc.storyUrlTemplates.firstOrNull()
        if (template != null) {
            return template.replace("%d", s).replace("%s", s).replace("%S", s)
        }
        return absUrl(baseUrl, "/$s/")
    }

    private fun absUrl(base: String, href: String): String {
        val h = href.trim()
        if (h.isBlank() || h.startsWith("http://") || h.startsWith("https://")) return h
        return try { java.net.URI(base).resolve(h).toString() } catch (e: Exception) { h }
    }

    /** Bo cac duoi rac hay dinh kem ten truyen tren trang ket qua. */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = t.removePrefix("Truyện ").trim()
        t = t.replace(Regex("(?i)\\s*[-|–]\\s*(full|hoan thanh|hoàn thành|convert|chương\\s*\\d+).*$"), "").trim()
        return t
    }

    /** Dia chi co giong trang gioi thieu 1 bo truyen khong. */
    private fun isStoryUrl(url: String, sc: SearchConfig): Boolean {
        if (url.isBlank()) return false
        val exc = sc.excludeUrlRegex
        if (!exc.isNullOrBlank()) {
            val re = compileRegexOrNull(exc)
            if (re != null && re.containsMatchIn(url)) return false
        }
        val inc = sc.storyUrlRegex
        if (inc.isNullOrBlank()) return true
        // Regex cau hinh viet sai cu phap -> coi nhu KHONG co rang buoc va ghi
        // log 1 lan. Ban cu bien loi bien dich regex thanh "khong URL nao khop"
        // nen chi 1 loi go trong parser.json la ca nguon bien mat khoi tim kiem.
        val re = compileRegexOrNull(inc) ?: return true
        return re.containsMatchIn(url)
    }

    private val brokenRegexLogged = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun compileRegexOrNull(pattern: String): Regex? =
        try { Regex(pattern) } catch (e: Exception) {
            if (brokenRegexLogged.add(pattern)) {
                android.util.Log.w("SearchEngine", "Regex cau hinh khong hop le, bo qua: $pattern (${e.message})")
            }
            null
        }

    // ---------- tien ich doc truong ----------
    private fun elementOf(root: Element, f: FieldSel?): Element? {
        val sel = f?.selector ?: return null
        return try {
            val found = root.select(sel)
            val idx = f.elementIndexed ?: 0
            // Index cau hinh nam ngoai tam = TRUOT (null). Ban cu am tham lay
            // phan tu dau tien thay the -> gan link/tac gia sai ma khong ai biet.
            if (found.isEmpty()) null else found.getOrNull(idx)
        } catch (e: Exception) { null }
    }

    private fun textOf(root: Element, f: FieldSel?): String =
        elementOf(root, f)?.text()?.trim().orEmpty()

    private fun attrOf(root: Element, f: FieldSel?, fallbackAttr: String): String? {
        val el = elementOf(root, f) ?: return null
        val name = f?.value?.takeIf { it.isNotBlank() && it != "text" } ?: fallbackAttr
        val v = el.absUrl(name).ifBlank { el.attr(name) }
        return v.trim().ifBlank { null }
    }

    /** Anh bia: thu src, roi cac thuoc tinh lazy-load pho bien. */
    private fun coverOf(row: Element, sc: SearchConfig): String {
        val el = elementOf(row, sc.cover) ?: row.selectFirst("img") ?: return ""
        return imgSrc(el)
    }

    private fun imgSrc(el: Element): String {
        for (attr in listOf("src", "data-src", "data-original", "data-lazy-src", "data-cfsrc")) {
            val v = el.absUrl(attr).ifBlank { el.attr(attr) }.trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        return ""
    }

    private fun titleAttr(row: Element, sc: SearchConfig): String =
        elementOf(row, sc.link)?.attr("title")?.trim().orEmpty()
}
