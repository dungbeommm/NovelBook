package vn.tramtruyen.reader.engine

object BrowserOpenPolicy {
    const val HOME = "file:///android_asset/browser_home.html"
    fun resolve(explicit: String? = null, chapterUrl: String? = null, storyUrl: String? = null): String {
        if (explicit == HOME) return HOME
        return listOf(explicit, chapterUrl, storyUrl).firstOrNull { value ->
            runCatching {
                val uri = java.net.URI(value?.trim().orEmpty())
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }?.trim() ?: HOME
    }
}
