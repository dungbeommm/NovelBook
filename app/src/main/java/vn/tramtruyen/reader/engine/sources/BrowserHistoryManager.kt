package vn.tramtruyen.reader.engine.sources

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Lịch sử riêng của browser hiển thị; các lượt scraper chạy ngầm không được ghi. */
object BrowserHistoryManager {
    data class Entry(
        val url: String,
        val title: String,
        val visitedAt: Long,
    )

    private const val PREFS = "browser_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 120

    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    val items: StateFlow<List<Entry>> = _items.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
        _items.value = decode(raw).sortedByDescending(Entry::visitedAt).take(MAX_ITEMS)
    }

    @Synchronized
    fun record(context: Context? = appContext, url: String, title: String) {
        val app = context?.applicationContext ?: return
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim().ifBlank {
            runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(url)
        }
        val current = _items.value
        val first = current.firstOrNull()
        val next = if (first?.url == url && now - first.visitedAt < 10_000L) {
            listOf(first.copy(title = cleanTitle, visitedAt = now)) + current.drop(1)
        } else {
            listOf(Entry(url, cleanTitle, now)) + current.filterNot { it.url == url }
        }.take(MAX_ITEMS)
        _items.value = next
        persist(app, next)
    }

    @Synchronized
    fun remove(context: Context? = appContext, entry: Entry) {
        val app = context?.applicationContext ?: return
        val next = _items.value.filterNot { it.url == entry.url && it.visitedAt == entry.visitedAt }
        _items.value = next
        persist(app, next)
    }

    @Synchronized
    fun deleteStoryUrls(urls: Set<String>) {
        val app = appContext ?: return
        val next = _items.value.filterNot { entry -> urls.any { base ->
            val normalized = vn.tramtruyen.reader.model.StoryIds.normalizeUrl(entry.url)
            normalized == base || (base.substringAfter("://", "").contains('/') &&
                !base.endsWith('/') && normalized.startsWith("$base/"))
        } }
        _items.value = next
        val array = JSONArray()
        next.forEach { entry -> array.put(JSONObject().put("url", entry.url)
            .put("title", entry.title).put("visitedAt", entry.visitedAt)) }
        check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString()).commit()) { "Không xoá được lịch sử trình duyệt" }
    }

    @Synchronized
    fun clear(context: Context? = appContext) {
        val app = context?.applicationContext ?: return
        _items.value = emptyList()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ITEMS).apply()
    }

    private fun persist(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("url", entry.url)
                put("title", entry.title)
                put("visitedAt", entry.visitedAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun decode(raw: String?): List<Entry> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (url.isBlank()) continue
                add(Entry(
                    url = url,
                    title = item.optString("title").ifBlank { url },
                    visitedAt = item.optLong("visitedAt", 0L),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
