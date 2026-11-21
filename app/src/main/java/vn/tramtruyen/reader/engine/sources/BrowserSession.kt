package vn.tramtruyen.reader.engine.sources

import android.annotation.SuppressLint
import android.app.Activity
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import vn.tramtruyen.reader.engine.BlockedException
import vn.tramtruyen.reader.engine.Config
import vn.tramtruyen.reader.engine.ReadingPreferences
import vn.tramtruyen.reader.engine.network.ProxyManager
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Một phiên Chromium dùng chung cho cả tải chương chạy ngầm và cửa sổ trình duyệt.
 *
 * WebView được giữ sống trong suốt tiến trình để tái sử dụng cache, cookie, kết nối và
 * trạng thái chống bot. SourceBrowserActivity chỉ gắn WebView này vào màn hình; đóng Activity
 * sẽ trả nó về chế độ chạy ngầm chứ không hủy phiên.
 */
object BrowserSession {
    interface Observer {
        fun onUserNavigation(url: String): Boolean = false
        fun onUrlChanged(url: String) {}
        fun onTitleChanged(title: String) {}
        fun onProgressChanged(progress: Int) {}
        fun onNavigationChanged(canGoBack: Boolean, canGoForward: Boolean) {}
    }

    private const val PAGE_LOAD_TIMEOUT_MS = 25_000L
    private const val POLL_INTERVAL_MS = 400L
    private const val MAX_POLLS = 50
    private const val HARD_TIMEOUT_EXTRA_MS = 8_000L
    /** Trang chủ mặc định của trình duyệt: catalog nguồn truyện được đóng gói trong assets. */
    private var clearHistoryAfterBlank = false
    const val HOME_URL = "file:///android_asset/browser_home.html"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val fetchMutex = Mutex()

    @Volatile private var visible = false
    @Volatile private var desktopMode = false
    private var webView: WebView? = null
    private var contextWrapper: MutableContextWrapper? = null
    private var observer = WeakReference<Observer>(null)
    private var attachedActivity = WeakReference<Activity>(null)
    private var pending: PendingLoad? = null
    private var navigationGeneration = 0L
    private var navigationUrl: String? = null
    private var loadingMainFrame = false
    private val stvChapterScript by lazy {
        checkNotNull(Config.appContext).assets.open("sangtacviet_chapter.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    class ChapterSnapshot internal constructor(
        val url: String,
        val html: String,
        internal val generation: Long,
    )

    private data class PendingLoad(
        val url: String,
        val config: SiteConfig,
        val finish: (Result<String>) -> Unit,
        var polls: Int = 0,
        var completed: Boolean = false,
        /** Da co chuoi poll chay cho job nay chua (moi job chi 1 chuoi). */
        var pollStarted: Boolean = false,
        /** onPageFinished da ban cho dung trang cua job chua. */
        var pageFinished: Boolean = false,
        val startedAtMs: Long = System.currentTimeMillis(),
        val currentGeneration: Long? = null,
    )

    suspend fun fetchRendered(url: String, config: SiteConfig, useProxy: Boolean = false): String =
        fetchMutex.withLock {
            val budget = PAGE_LOAD_TIMEOUT_MS + MAX_POLLS * POLL_INTERVAL_MS + HARD_TIMEOUT_EXTRA_MS
            var proxyOn = false
            try {
                if (useProxy) proxyOn = ProxyManager.acquireWebViewProxy()
                withTimeoutOrNull(budget) { loadForExtraction(url, config).html }
                    ?: throw RuntimeException("WebView hết thời gian: $url")
            } finally {
                if (proxyOn) ProxyManager.releaseWebViewProxy()
            }
        }

    /** Capture only the document the reader is viewing. Never navigate from this action. */
    suspend fun captureCurrentChapter(url: String): ChapterSnapshot =
        withTimeoutOrNull(8_000L) {
            val config = SiteConfigStore.findForUrl(url) ?: SiteConfig()
            fetchMutex.withLock { loadForExtraction(url, config, currentOnly = true) }
        } ?: throw IllegalStateException("Trang chưa sẵn sàng. Hãy đợi nội dung chương hiển thị rồi thử lại.")

    fun isCurrent(snapshot: ChapterSnapshot): Boolean =
        visible && !loadingMainFrame && snapshot.generation == navigationGeneration && snapshot.url == currentUrl()

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreate(): WebView {
        webView?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView phải được tạo trên main thread" }
        val app = Config.appContext ?: error("appContext null")
        val wrapper = MutableContextWrapper(app)
        contextWrapper = wrapper
        return WebView(wrapper).also { web ->
            with(web.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = visible
                blockNetworkImage = !visible
                userAgentString = MOBILE_UA
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(web, stvChapterScript, SangTacVietChapter.origins)
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    observer.get()?.onProgressChanged(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) observer.get()?.onTitleChanged(title)
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && request.hasGesture() &&
                        observer.get()?.onUserNavigation(request.url.toString()) == true

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    navigationGeneration++
                    navigationUrl = url
                    loadingMainFrame = true
                    url?.let { observer.get()?.onUrlChanged(it) }
                    notifyNavigation(web)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    // History API and fragment changes can occur without onPageStarted.
                    if (url != navigationUrl) {
                        navigationGeneration++
                        navigationUrl = url
                        url?.let { observer.get()?.onUrlChanged(it) }
                        notifyNavigation(web)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == web.url) loadingMainFrame = false
                    if (clearHistoryAfterBlank) {
                        if (url != "about:blank") return
                        web.clearHistory()
                        clearHistoryAfterBlank = false
                    }
                    url?.let { observer.get()?.onUrlChanged(it) }
                    notifyNavigation(web)
                    // Gac quang cao: xoa banner toan trang treo ngoai <body>
                    // (vd Monetag) ngay khi trang tai xong. Chi tiem khi nguoi
                    // dung dang bat trinh chan quang cao trong Cai dat.
                    if (AdBlockManager.state.value.enabled &&
                        !url.isNullOrBlank() &&
                        (url.startsWith("https://") || url.startsWith("http://"))
                    ) {
                        val guard = bannerGuardJs()
                        if (guard.isNotBlank()) {
                            try { view?.evaluateJavascript(guard, null) } catch (_: Exception) {}
                        }
                        val cosmetic = cosmeticJs()
                        if (cosmetic.isNotBlank()) {
                            try { view?.evaluateJavascript(cosmetic, null) } catch (_: Exception) {}
                        }
                    }
                    if (visible && !url.isNullOrBlank()) {
                        BrowserHistoryManager.record(
                            context = Config.appContext,
                            url = url,
                            title = view?.title.orEmpty(),
                        )
                    }
                    val job = pending ?: return
                    // CHI phuc vu job khi trang vua tai xong DUNG la trang job yeu
                    // cau (cho phep redirect cung host). WebView dung chung voi
                    // trinh duyet hien thi: khong doi chieu URL thi nguoi dung bam
                    // link giua luc tai ngam se khien job an HTML cua trang dang
                    // xem lam noi dung chuong.
                    if (!job.completed && pageMatchesJob(url, job)) {
                        job.pageFinished = true
                        schedulePoll(web, job)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val job = pending ?: return
                        complete(job, Result.failure(RuntimeException(
                            "WebView main-frame error ${error?.errorCode ?: -1}: " +
                                error?.description?.toString().orEmpty()
                        )))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
                ) {
                    val job = pending ?: return
                    val code = errorResponse?.statusCode ?: return
                    if (request?.isForMainFrame == true && pageMatchesJob(request.url.toString(), job) &&
                        SangTacVietChapter.matches(job.url) && code in listOf(401, 403, 429)) {
                        complete(job, Result.failure(BlockedException(code)))
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null || request.isForMainFrame) return null
                    val url = request.url.toString().lowercase()
                    // Luat chặn URL quảng cáo được kiểm tra trước khi gửi request.
                    // ca khi browser dang hien thi va luc chay ngam.
                    if (AdBlockManager.shouldBlock(url)) return emptyResponse()
                    if (visible) return null
                    val blockedAsset = url.matches(Regex(".*\\.(woff2?|ttf|otf|mp4|webm|mp3|m4a)(\\?.*)?$"))
                    val tracker = listOf(
                        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
                        "facebook.net/tr", "connect.facebook.net", "hotjar.com"
                    ).any(url::contains)
                    return if (blockedAsset || tracker) emptyResponse() else null
                }
            }
            webView = web
        }
    }

    private suspend fun loadForExtraction(
        url: String,
        config: SiteConfig,
        currentOnly: Boolean = false,
    ): ChapterSnapshot =
        suspendCancellableCoroutine { cont ->
            main.post {
                val web = runCatching { getOrCreate() }.getOrElse {
                    if (cont.isActive) cont.resumeWith(Result.failure(it))
                    return@post
                }
                if (!cont.isActive) return@post
                if (currentOnly && (!visible || web.url != url)) {
                    cont.resumeWith(Result.failure(IllegalStateException("Trang đã thay đổi. Hãy bấm nghe lại trên chương đang mở.")))
                    return@post
                }
                cancelPending("Đã bắt đầu yêu cầu tải mới")
                if (!currentOnly) setBackgroundNetworkMode(web)
                val generation = navigationGeneration
                lateinit var job: PendingLoad
                job = PendingLoad(url, config, finish = { result: Result<String> ->
                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                    if (cont.isActive) cont.resumeWith(result.map { ChapterSnapshot(url, it, generation) })
                }, currentGeneration = generation.takeIf { currentOnly })
                pending = job
                // Han tai trang THAT: neu onPageFinished khong ban trong
                // PAGE_LOAD_TIMEOUT_MS ke tu luc bat dau tai thi huy job ngay,
                // thay vi treo khoa render toan cuc cho toi han tong ~53s.
                main.postDelayed({
                    if (pending === job && !job.completed && !job.pageFinished) {
                        complete(job, Result.failure(RuntimeException(
                            "WebView qua thoi gian tai trang: $url"
                        )))
                    }
                }, PAGE_LOAD_TIMEOUT_MS)
                cont.invokeOnCancellation {
                    main.post {
                        if (pending === job) complete(job, Result.failure(RuntimeException("cancelled")))
                    }
                }
                // “Nghe chương này” dùng ngay DOM đang hiển thị thay vì tải lại.
                // Nhờ vậy session đăng nhập, nội dung JS và vị trí trang được giữ nguyên.
                if (currentOnly || sameDocument(web.url, url)) {
                    job.pageFinished = true
                    schedulePoll(web, job)
                } else {
                    runCatching {
                        web.loadUrl(
                            url,
                            mapOf("Accept-Language" to ReadingPreferences.acceptLanguageHeader()),
                        )
                    }
                        .onFailure { complete(job, Result.failure(it)) }
                }
            }
        }

    private fun schedulePoll(web: WebView, job: PendingLoad) {
        // Moi job chi co DUNG MOT chuoi poll. onPageFinished ban nhieu lan
        // (redirect, tai lai) ma spawn them chuoi song song thi moi chuoi cung
        // tang job.polls -> MAX_POLLS bi cham som gap 2-3 lan.
        if (job.completed || job.pollStarted) return
        job.pollStarted = true
        if (SangTacVietChapter.matches(job.url)) {
            pollSangTacVietChapter(web, job)
            return
        }
        val readyJs = buildReadyJs(job.config)
        val captureJs = if (job.currentGeneration != null) {
            val script = try {
                checkNotNull(Config.appContext).assets.open("browser_chapter_snapshot.js")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (error: Exception) {
                complete(job, Result.failure(error))
                return
            }
            val selector = job.config.content?.selector?.let { JsonPrimitive(it).toString() } ?: "null"
            "$script(${JsonPrimitive(job.url)}, $selector)"
        } else {
            "(document.documentElement?document.documentElement.outerHTML:'')"
        }
        val poll = object : Runnable {
            fun retry() {
                if (job.polls >= MAX_POLLS) {
                    complete(job, Result.failure(RuntimeException(
                        "Trang không sẵn sàng sau $MAX_POLLS lần kiểm tra: ${job.url}"
                    )))
                } else {
                    job.polls++
                    main.postDelayed(this, POLL_INTERVAL_MS)
                }
            }

            override fun run() {
                if (pending !== job || job.completed) return
                if (job.currentGeneration != null && !pageMatchesJob(web.url, job)) {
                    complete(job, Result.failure(IllegalStateException("Trang đã thay đổi. Hãy bấm nghe lại trên chương đang mở.")))
                    return
                }
                if (job.currentGeneration != null && loadingMainFrame) {
                    retry()
                    return
                }
                web.evaluateJavascript(readyJs) { raw ->
                    if (pending !== job || job.completed) return@evaluateJavascript
                    val state = decode(raw)
                    // Trang phai con la trang cua job: WebView dung chung co the
                    // da bi dieu huong sang trang khac giua luc poll.
                    if (state == "READY" && pageMatchesJob(web.url, job)) {
                        web.evaluateJavascript(captureJs) { html ->
                            if (pending === job && pageMatchesJob(web.url, job)) {
                                val snapshot = decode(html)
                                if (snapshot.isBlank()) retry()
                                else complete(job, Result.success(snapshot))
                            } else if (pending === job) {
                                complete(job, Result.failure(IllegalStateException("Trang đã thay đổi lúc lấy nội dung")))
                            }
                        }
                    } else {
                        retry()
                    }
                }
            }
        }
        main.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun pollSangTacVietChapter(web: WebView, job: PendingLoad) {
        val script = runCatching { stvChapterScript }.getOrElse {
            complete(job, Result.failure(it))
            return
        }
        val expected = JsonPrimitive(job.url).toString()
        val read = """
            (function() {
                if (location.href !== $expected) return JSON.stringify({state: 'navigation'});
                $script
                return window.tramTruyenStvChapter?.($expected);
            })();
        """.trimIndent()
        val poll = object : Runnable {
            override fun run() {
                if (pending !== job || job.completed) return
                if (!pageMatchesJob(web.url, job)) {
                    complete(job, Result.failure(IllegalStateException("Trang đã chuyển sang địa chỉ khác.")))
                    return
                }
                web.evaluateJavascript(read) { raw ->
                    if (pending !== job || job.completed) return@evaluateJavascript
                    val result = runCatching { json.parseToJsonElement(decode(raw)).jsonObject }.getOrNull()
                    val state = result?.get("state")?.jsonPrimitive?.contentOrNull
                    val html = result?.get("html")?.jsonPrimitive?.contentOrNull.orEmpty()
                    when {
                        !pageMatchesJob(web.url, job) || state == "navigation" ->
                            complete(job, Result.failure(IllegalStateException("Trang đã chuyển sang địa chỉ khác.")))
                        state == "blocked" ->
                            complete(job, Result.failure(IllegalStateException("Sáng Tác Việt yêu cầu xác minh. Hãy mở trang nguồn để xác minh.")))
                        !loadingMainFrame && state == "ready" && html.isNotBlank() ->
                            complete(job, Result.success(html))
                        ++job.polls >= MAX_POLLS ->
                            complete(job, Result.failure(IllegalStateException("Sáng Tác Việt chưa hiển thị văn bản chương.")))
                        else -> main.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        }
        main.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** Trang vua tai co phai trang job dang cho khong (chap nhan redirect cung host). */
    private fun pageMatchesJob(finishedUrl: String?, job: PendingLoad): Boolean {
        job.currentGeneration?.let {
            return visible && it == navigationGeneration && finishedUrl == job.url
        }
        if (SangTacVietChapter.matches(job.url)) return finishedUrl == job.url
        val finished = finishedUrl?.trim().orEmpty()
        if (finished.isBlank()) return false
        fun norm(u: String): String = u.substringBefore('#').trimEnd('/')
        if (norm(finished) == norm(job.url)) return true
        val finishedHost = runCatching { Uri.parse(finished).host }.getOrNull()
        val jobHost = runCatching { Uri.parse(job.url).host }.getOrNull()
        return !finishedHost.isNullOrBlank() && finishedHost.equals(jobHost, ignoreCase = true)
    }

    private fun complete(job: PendingLoad, result: Result<String>) {
        if (job.completed) return
        job.completed = true
        if (pending === job) pending = null
        job.finish(result)
    }

    private fun cancelPending(reason: String) {
        pending?.let { complete(it, Result.failure(RuntimeException(reason))) }
    }

    /** Gắn đúng WebView đang tải ngầm vào cửa sổ trình duyệt. */
    fun attach(activity: Activity, container: ViewGroup, newObserver: Observer) {
        main.post {
            val web = getOrCreate()
            visible = true
            attachedActivity = WeakReference(activity)
            observer = WeakReference(newObserver)
            contextWrapper?.baseContext = activity
            (web.parent as? ViewGroup)?.removeView(web)
            container.addView(web, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            web.settings.loadsImagesAutomatically = true
            web.settings.blockNetworkImage = false
            web.resumeTimers()
            newObserver.onUrlChanged(web.url.orEmpty())
            newObserver.onTitleChanged(web.title.orEmpty())
            newObserver.onProgressChanged(web.progress)
            notifyNavigation(web)
        }
    }

    /** Tháo khỏi Activity nhưng giữ WebView, cookie và trang hiện tại để tiếp tục chạy ngầm. */
    fun detach(activity: Activity) {
        main.post {
            if (attachedActivity.get() !== activity) return@post
            val web = webView ?: return@post
            (web.parent as? ViewGroup)?.removeView(web)
            contextWrapper?.baseContext = Config.appContext ?: activity.applicationContext
            observer.clear()
            attachedActivity.clear()
            visible = false
            setBackgroundNetworkMode(web)
        }
    }

    fun navigate(input: String) = main.post {
        val web = getOrCreate()
        cancelPending("Người dùng đã điều hướng trình duyệt")
        web.settings.loadsImagesAutomatically = true
        web.settings.blockNetworkImage = false
        web.loadUrl(normalizeInput(input))
    }

    fun home() = navigate(HOME_URL)
    fun reload() = main.post { webView?.reload() }
    suspend fun clearStoryData(urls: Set<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val web = webView ?: return@withContext
        cancelPending("Truyện đã bị xoá")
        web.stopLoading()
        // KHONG clearCache(true) / clearHistory() toan cuc nua: xoa 1 truyen ma
        // xoa sach cache + toan bo lich su duyet web cua MOI site la qua tay
        // (WebView khong co API xoa cache theo URL). Thay vao do: het han cookie
        // cua rieng cac host thuoc truyen bi xoa, va dieu huong khoi trang cua
        // truyen do neu dang mo.
        expireCookiesForHosts(urls)
        val current = vn.tramtruyen.reader.model.StoryIds.normalizeUrl(web.url)
        if (urls.any { current == it || current.startsWith("$it/") }) {
            web.loadUrl("about:blank")
        }
    }

    /** Het han cookie theo tung host cua truyen bi xoa (pham vi hep, thay cho xoa toan cuc). */
    private fun expireCookiesForHosts(urls: Set<String>) {
        val cm = CookieManager.getInstance()
        val hosts = urls.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }.distinct()
        for (host in hosts) {
            val base = "https://$host/"
            val raw = runCatching { cm.getCookie(base) }.getOrNull().orEmpty()
            for (part in raw.split(';')) {
                val name = part.substringBefore('=').trim()
                if (name.isEmpty()) continue
                runCatching {
                    cm.setCookie(base, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                }
            }
        }
        runCatching { cm.flush() }
    }

    fun stopLoading() = main.post { webView?.stopLoading() }
    fun goBack(): Boolean {
        val web = webView ?: return false
        return if (web.canGoBack()) { main.post { web.goBack() }; true } else false
    }
    fun goForward() = main.post { webView?.takeIf { it.canGoForward() }?.goForward() }
    fun currentUrl(): String = webView?.url.orEmpty()
    fun currentTitle(): String = webView?.title.orEmpty()
    fun isDesktopMode(): Boolean = desktopMode

    fun setDesktopMode(enabled: Boolean) = main.post {
        desktopMode = enabled
        webView?.let { web ->
            web.settings.userAgentString = if (enabled) DESKTOP_UA else MOBILE_UA
            web.settings.useWideViewPort = enabled
            web.settings.loadWithOverviewMode = enabled
            web.reload()
        }
    }

    fun findInPage(query: String) = main.post {
        webView?.let { web ->
            if (query.isBlank()) web.clearMatches() else web.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) = main.post {
        webView?.findNext(forward)
    }

    fun clearFindMatches() = main.post {
        webView?.clearMatches()
    }

    private fun setBackgroundNetworkMode(web: WebView) {
        if (visible) return
        web.settings.loadsImagesAutomatically = false
        web.settings.blockNetworkImage = true
    }

    private fun notifyNavigation(web: WebView) {
        observer.get()?.onNavigationChanged(web.canGoBack(), web.canGoForward())
    }

    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    fun normalizeInput(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return HOME_URL
        if (value.startsWith(HOME_URL, true)) return value
        if (value.startsWith("file:///android_asset/", true)) return value
        if (value.startsWith("https://", true)) return value
        if (value.startsWith("http://", true)) return "https://" + value.substringAfter("://")
        val looksLikeHost = !value.contains(' ') && value.contains('.')
        return if (looksLikeHost) "https://$value"
        else "https://www.google.com/search?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun sameDocument(first: String?, second: String?): Boolean {
        fun clean(value: String?): String = value.orEmpty()
            .substringBefore('#')
            .trimEnd('/')
        return clean(first).isNotBlank() && clean(first) == clean(second)
    }

    @Volatile private var cosmeticCache: String? = null

    /** Drop cached scripts after the downloadable ad-block extension is replaced. */
    fun invalidateAdBlockExtensionScripts() {
        cosmeticCache = null
        bannerGuardCache = null
    }

    /** Script CSS ẩn các phần tử quảng cáo đã nhận diện. */
    private fun cosmeticJs(): String {
        cosmeticCache?.let { return it }
        val text = adBlockScript("adblock_cosmetic.js")
        cosmeticCache = text
        return text
    }

    @Volatile private var bannerGuardCache: String? = null

    /** Script loại bỏ các quảng cáo dạng lớp phủ — nạp một lần và dùng lại. */
    private fun bannerGuardJs(): String {
        bannerGuardCache?.let { return it }
        val text = adBlockScript("browser_ad_guard.js")
        bannerGuardCache = text
        return text
    }

    private fun adBlockScript(fileName: String): String {
        val context = Config.appContext ?: return ""
        val extensionDir = File(context.filesDir, "adblock_extension")
        val manifest = File(extensionDir, "manifest.json")
        val currentExtension = runCatching {
            JSONObject(manifest.readText(Charsets.UTF_8))
                .optString("rules_format") == "tramtruyen-adblock-rules-v1"
        }.getOrDefault(false)
        val downloaded = File(extensionDir, fileName)
        if (currentExtension && downloaded.isFile) {
            runCatching { downloaded.readText(Charsets.UTF_8) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }

    private fun buildReadyJs(config: SiteConfig): String {
        val enabled = AdBlockManager.state.value.enabled
        val guard = if (enabled) bannerGuardJs() else ""
        val cosmetic = if (enabled) cosmeticJs() else ""
        val loadJs = config.contentLoadJs ?: ""
        val waitArr = jsArray(config.dataReadyChecker)
        val loadArr = jsArray(config.dataLoadingChecker)
        val contentSel = config.content?.selector?.let { JsonPrimitive(it).toString() } ?: "null"
        return """
        (function(){
          try { $loadJs } catch(e){}
          try { $guard } catch(e){}
          try { $cosmetic } catch(e){}
          try {
            var waitTexts=$waitArr, body=(document.body&&document.body.innerText)||'';
            for(var i=0;i<waitTexts.length;i++){
              var w=waitTexts[i]; if(!w) continue;
              if(body.indexOf(w)>=0) return 'WAIT';
              try{if(document.querySelector('.'+w)) return 'WAIT';}catch(e){}
              try{var n=document.getElementById(w);if(n&&n.offsetParent!==null)return 'WAIT';}catch(e){}
            }
            var loadIds=$loadArr;
            for(var j=0;j<loadIds.length;j++){
              var id=loadIds[j]; if(!id) continue;
              try{var e=document.getElementById(id)||document.querySelector('.'+id);if(e&&e.offsetParent!==null)return 'WAIT';}catch(e){}
            }
            var sel=$contentSel;
            if(!sel) sel='.chapter-content, #chapter-content, #chapter-c, .chapter-c, .reader-content, section[itemprop=text], .prose-novel';
            if(sel){try{var c=document.querySelector(sel);if(c&&((c.textContent||'').trim().length>0)&&document.readyState!=='loading')return 'READY';}catch(e){}}
            // Fallback theo do dai text KHONG duoc chap nhan trang thach chong
            // bot / tuong dang nhap: cac trang do deu dai hon 600 ky tu nen ban
            // cu nhan chung lam noi dung that.
            var lowBody=body.toLowerCase();
            var blocked = lowBody.indexOf('verify you are human')>=0 ||
              lowBody.indexOf('checking your browser')>=0 ||
              lowBody.indexOf('just a moment')>=0 ||
              document.querySelector('#challenge-form, #cf-challenge-running, .cf-browser-verification, iframe[src*="challenges.cloudflare.com"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"], input[type="password"]');
            if(blocked) return 'WAIT';
            // Fallback phai thay noi dung dang DOAN VAN that chu khong chi la
            // mot khoi text lon (menu/footer/trang loi cung dai).
            var pLen=0; var ps=document.querySelectorAll('p');
            for(var k=0;k<ps.length;k++){ pLen+=(ps[k].innerText||'').trim().length; }
            if(body.trim().length>600 && pLen>150) return 'READY';
          }catch(e){}
          return 'WAIT';
        })();
        """.trimIndent()
    }

    private fun jsArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") { JsonPrimitive(it).toString() }

    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { json.parseToJsonElement(raw).jsonPrimitive.contentOrNull ?: "" }
            .getOrElse { raw.trim('"').replace("\\n", "\n").replace("\\\"", "\"") }
    }
}
