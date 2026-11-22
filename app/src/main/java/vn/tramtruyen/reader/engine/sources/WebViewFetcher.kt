package vn.tramtruyen.reader.engine.sources

/**
 * API tương thích cho pipeline cũ. Việc render thật được giữ trong BrowserSession
 * để cùng một WebView có thể chạy ngầm và được mở thành trình duyệt bất kỳ lúc nào.
 */
object WebViewFetcher {
    suspend fun fetchRendered(
        url: String,
        config: SiteConfig,
        useProxy: Boolean = false,
    ): String = BrowserSession.fetchRendered(url, config, useProxy)
}
