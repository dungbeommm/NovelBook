package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.BlockedException
import vn.tramtruyen.reader.engine.HttpClient
import vn.tramtruyen.reader.engine.network.ProxyManager

/**
 * BO LAY TRANG THONG NHAT (thay the viec goi thang HttpClient.fetch o khap noi).
 *
 * Mọi trang truyện đi qua BrowserSession trước. Đây là cùng phiên WebView mà người dùng
 * có thể mở từ Cài đặt, vì vậy cookie/đăng nhập/chống bot và nội dung JavaScript được giữ
 * nguyên giữa lúc chạy ngầm và lúc hiển thị như một trình duyệt thật.
 *
 * OkHttp chỉ còn là đường dự phòng khi thiết bị không có WebView hoặc Chromium lỗi/hết giờ.
 */
object PageFetcher {

    data class Fetched(val html: String, val config: SiteConfig?)

    /**
     * @param url        URL chuong/trang can lay.
     * @param httpClient client OkHttp da cau hinh delay san (caller truyen vao de tai su dung).
     */
    suspend fun fetch(url: String, httpClient: HttpClient): Fetched {
        if (SangTacVietChapter.matches(url)) {
            val html = GeckoFeature.fetchIfInstalled(url) ?: WebViewFetcher.fetchRendered(url, SiteConfig())
            return Fetched(html, null)
        }
        val config = SiteConfigStore.findForUrl(url)
        val wantProxy = ProxyManager.shouldUseProxy(config?.mayNeedVpn == true)
        // Chi bat proxy khi thuc su chon duoc 1 proxy song.
        val useProxy = wantProxy && ProxyManager.ensureProxy() != null

        val html = try {
            WebViewFetcher.fetchRendered(url, config ?: SiteConfig(), useProxy)
        } catch (browserError: Exception) {
            if (browserError is BlockedException) throw browserError
            try {
                fetchHttp(url, httpClient, useProxy)
            } catch (httpError: Exception) {
                // Giữ lỗi HTTP có mã/chẩn đoán rõ ràng; nếu không có thì trả lỗi WebView.
                if (httpError is BlockedException || looksBlocked(httpError)) throw httpError
                throw browserError
            }
        }
        return Fetched(html, config)
    }

    /** Nhan dien loi "bi chan" de quyet dinh co lui ve WebView an hay khong. */
    private fun looksBlocked(e: Throwable): Boolean {
        if (e is BlockedException) return true
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return m.contains("403") || m.contains("429") || m.contains("503") ||
            m.contains("timeout", true) || m.contains("reset", true) || m.contains("refused", true)
    }

    /** Tai HTML bang OkHttp, uu tien qua proxy; neu proxy hong thi danh dau + thu truc tiep. */
    private suspend fun fetchHttp(url: String, httpClient: HttpClient, useProxy: Boolean): String {
        if (!useProxy) return httpClient.fetch(url, useProxy = false)
        return try {
            httpClient.fetch(url, useProxy = true)
        } catch (e: Exception) {
            ProxyManager.markBad()
            httpClient.fetch(url, useProxy = false)
        }
    }
}
