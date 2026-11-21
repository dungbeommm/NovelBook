package vn.tramtruyen.reader.engine

import vn.tramtruyen.reader.engine.network.AppDns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.tramtruyen.reader.engine.network.ProxyManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Nem khi bi CHAN (403/429/503) — de lop tren tu dong lui ve WebView an de vuot chan. */
class BlockedException(val code: Int) : RuntimeException("HTTP $code (bi chan)")

/**
 * CookieJar luu trong bo nho (theo host) — giu phien/cookie giua cac request cua cung
 * 1 site. Nhieu trang chong bot set cookie o lan dau roi moi cho xem noi dung; giu cookie
 * giup cac request sau di qua duoc.
 */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(list) { list.filter { it.expiresAt > now && it.matches(url) } }
    }
}

/**
 * HTTP client cho scraping — port cua scrapers/http_client.py.
 * Dung OkHttp + danh sach User-Agent tinh + delay + retry (403/429/503 -> thu lai).
 * Chong chan: xoay User-Agent, gia Referer/Sec-Fetch, giu Cookie phien.
 */
class HttpClient(
    private val minDelay: Double = Config.MIN_DELAY,
    private val maxDelay: Double = Config.MAX_DELAY,
    private val maxRetries: Int = Config.MAX_RETRIES,
) {
    private val delayMutex = Mutex()

    // Tai su dung 1 OkHttpClient dung chung (connection pool + thread pool) thay vi
    // tao moi moi request/download -> giam cap phat, giam do tre, on dinh hon.
    private val client: OkHttpClient get() = sharedClient

    companion object {
        /** OkHttpClient dung chung cho toan bo app — chi khoi tao 1 lan (lazy, thread-safe). */
        val sharedClient: OkHttpClient by lazy {
            AppDns.clientBuilder()
                .connectTimeout(Config.REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(Config.REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(Config.REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(Config.REQUEST_TIMEOUT_SEC + 15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(InMemoryCookieJar())
                .build()
        }

        // fetchFast() được gọi song song khi tìm kiếm nhiều nguồn. newBuilder()
        // vẫn chia sẻ pool nhưng việc build lại client ở từng request tạo thêm
        // object và cấu hình timeout không cần thiết. Cache theo timeout (thực tế
        // chỉ có vài mức cố định) để đường tìm kiếm nóng không phát sinh rác.
        private val fastClients = ConcurrentHashMap<Long, OkHttpClient>()

        private fun fastClient(timeoutMs: Long): OkHttpClient {
            val bounded = timeoutMs.coerceIn(2_000L, 30_000L)
            return fastClients.getOrPut(bounded) {
                sharedClient.newBuilder()
                    .connectTimeout((bounded / 2).coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
                    .readTimeout(bounded, TimeUnit.MILLISECONDS)
                    .callTimeout(bounded, TimeUnit.MILLISECONDS)
                    .build()
            }
        }

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
        )
    }

    /**
     * TAI NHANH cho TIM KIEM: 1 lan duy nhat, KHONG retry, KHONG delay lich su,
     * va co han gio o TANG OKHTTP (callTimeout) nen socket tu dut khi qua han.
     *
     * Ly do phai co ham nay: `fetch()` thuong retry 3 lan voi backoff 4s/8s/16s va
     * callTimeout 45s -> 1 site cham co the giu coroutine gan 2 phut. `withTimeoutOrNull`
     * cua coroutine KHONG cat duoc `execute()` dang chan luong, nen o man tim kiem
     * (chay song song hang chuc site) se thanh "quay vong mai khong ra".
     */
    suspend fun fetchFast(
        url: String,
        timeoutMs: Long = 8_000L,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val fast = fastClient(timeoutMs)
        val referer = try { val u = java.net.URI(url); "${u.scheme}://${u.authority}/" } catch (_: Exception) { null }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENTS.random())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", ReadingPreferences.acceptLanguageHeader())
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .apply { if (referer != null) header("Referer", referer) }
            // Header rieng cua tung nguon (lay tu extension vBook): mot so site
            // tu choi phuc vu neu thieu Referer/Origin/User-Agent dung dinh dang.
            .apply { for ((k, v) in extraHeaders) header(k, v) }
            .build()
        val call = fast.newCall(req)
        // Coroutine bi huy (nguoi dung go tiep chu khac) -> huy luon call OkHttp.
        try {
            call.execute().use { resp ->
                val code = resp.code
                if (code == 403 || code == 429 || code == 503) throw BlockedException(code)
                if (!resp.isSuccessful) throw RuntimeException("HTTP $code")
                resp.body?.bytes()?.let { HtmlCharsetDecoder.decode(it, resp.header("Content-Type")) } ?: ""
            }
        } finally {
            if (!call.isExecuted() || call.isCanceled()) call.cancel()
        }
    }

    suspend fun fetch(url: String, useProxy: Boolean = false): String {
        // Chi ap dung delay lich su (chong bi chan) khi TAI HANG LOAT.
        // Khi doc truc tiep (min/max delay = 0) thi bo qua de phat gan nhu tuc thi.
        if (maxDelay > 0.0 && maxDelay > minDelay) {
            delayMutex.withLock {
                val wait = Random.nextDouble(minDelay, maxDelay)
                delay((wait * 1000).toLong())
            }
        }
        return fetchWithRetry(url, useProxy)
    }

    /** Client co proxy (chia se connection pool voi sharedClient). null proxy -> tra ve client thuong. */
    private fun proxiedClient(): OkHttpClient {
        val p = ProxyManager.javaProxy() ?: return client
        return sharedClient.newBuilder().proxy(p).build()
    }

    private suspend fun fetchWithRetry(url: String, useProxy: Boolean = false): String = withContext(Dispatchers.IO) {
        val activeClient = if (useProxy) proxiedClient() else client
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < maxRetries) {
            try {
                // Referer gia theo goc cua chinh URL — nhieu site chan neu thieu Referer.
                val referer = try { val u = java.net.URI(url); "${u.scheme}://${u.authority}/" } catch (_: Exception) { null }
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENTS.random())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", ReadingPreferences.acceptLanguageHeader())
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    // Bo Sec-Fetch giong dieu huong tu trinh duyet that.
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                if (referer != null) reqBuilder.header("Referer", referer)
                val req = reqBuilder.build()
                activeClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if (code == 403 || code == 429 || code == 503) throw BlockedException(code)
                    if (!resp.isSuccessful) throw RuntimeException("HTTP $code")
                    return@withContext resp.body?.bytes()
                        ?.let { HtmlCharsetDecoder.decode(it, resp.header("Content-Type")) }
                        ?: ""
                }
            } catch (e: Exception) {
                // An explicit access block is not a transient transport error.
                // Stop instead of retrying the same protected request repeatedly.
                if (e is BlockedException) throw e
                lastErr = e
                attempt++
                if (attempt >= maxRetries) break
                val backoff = (Config.RETRY_BACKOFF * Math.pow(2.0, attempt.toDouble())).coerceIn(2.0, 30.0)
                delay((backoff * 1000).toLong())
            }
        }
        throw (lastErr ?: RuntimeException("fetch failed: $url"))
    }

    fun close() { /* OkHttp tu quan ly connection pool */ }
}
