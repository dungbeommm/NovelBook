package vn.tramtruyen.reader.engine.catalog

import android.content.Context
import vn.tramtruyen.reader.engine.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Kho parser engine v4 được đóng gói sẵn trong APK.
 *
 * Cau truc:
 *   catalog.json
 *   sources/[ten-mien]/manifest.json
 *   sources/[ten-mien]/[ten-mien].js
 *
 * Nguon engine v4 duoc uu tien hon parser.json dong goi cu trong app.
 */
object ParserSourceStore {

    data class Source(
        val id: String,
        val name: String,
        val domains: List<String>,
        val useWebview: Boolean,
        val useHtmlParser: Boolean,
        val mayNeedVpn: Boolean,
        val capabilities: List<String>,
        val version: Int,
        val engine: Int,
        val homepage: String,
        val logo: String,
        val scriptFile: File? = null,
        val scriptAssetPath: String? = null,
    ) {
        fun matches(host: String): Boolean {
            val h = host.lowercase()
            if (h.isBlank()) return false
            return domains.any { rawDomain ->
                val d = rawDomain.trim().trimStart('.').lowercase()
                d.isNotBlank() && (h == d || h.endsWith(".$d"))
            }
        }

        /** Độ dài domain khớp dài nhất (cụ thể nhất) với host; -1 nếu không khớp. */
        fun matchLength(host: String): Int {
            val h = host.lowercase()
            if (h.isBlank()) return -1
            return domains.mapNotNull { rawDomain ->
                val d = rawDomain.trim().trimStart('.').lowercase()
                if (d.isNotBlank() && (h == d || h.endsWith(".$d"))) d.length else null
            }.maxOrNull() ?: -1
        }

        fun scriptCacheKey(): String = scriptFile?.let {
            "file:${it.absolutePath}:${it.lastModified()}"
        } ?: "asset:${scriptAssetPath.orEmpty()}"

        fun readScript(): String {
            scriptFile?.let { return it.readText() }
            val path = scriptAssetPath ?: error("Parser $id không có script")
            val context = Config.appContext ?: error("appContext null")
            return context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        /** Engine v4 luon ho tro day du action chinh; capabilities chi la goi y. */
        fun can(cap: String): Boolean {
            if (capabilities.isEmpty() || engine >= 4) return true
            return capabilities.contains(cap)
        }
    }

    @Volatile private var filesRoot: File? = null
    @Volatile private var assetsRoot: String? = null
    @Volatile private var assetContext: Context? = null
    @Volatile private var sources: List<Source> = emptyList()
    @Volatile private var sourceByDomain: Map<String, Source> = emptyMap()
    @Volatile private var cachedDomains: List<String> = emptyList()
    @Volatile private var catalogEngine: Int = 4

    fun init(root: File) {
        assetContext = null
        assetsRoot = null
        filesRoot = root
        reload()
    }

    fun initAssets(context: Context, root: String) {
        filesRoot = null
        assetContext = context.applicationContext
        assetsRoot = root.trim('/')
        reloadAssets()
    }

    private fun parseCapabilities(o: JSONObject): List<String> {
        val arr = o.optJSONArray("capabilities") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun reload() {
        val root = filesRoot ?: return
        val dir = File(root, "sources")
        if (!dir.isDirectory) {
            publishSources(emptyList())
            return
        }

        val catalog = File(root, "catalog.json")
        // Thiếu hoặc hỏng catalog.json thì reset về engine mặc định 4,
        // không giữ giá trị cũ của lần nạp trước.
        catalogEngine = if (catalog.isFile) {
            runCatching { JSONObject(catalog.readText()).optInt("engine", 4) }.getOrDefault(4)
        } else {
            4
        }

        val out = ArrayList<Source>()
        dir.listFiles()?.sortedBy { it.name }?.forEach { sub ->
            if (!sub.isDirectory) return@forEach
            val manifest = File(sub, "manifest.json")
            if (!manifest.isFile) return@forEach
            runCatching {
                val o = JSONObject(manifest.readText())
                val id = o.optString("id", sub.name)
                val script = File(sub, "$id.js").takeIf { it.isFile }
                    // Fallback chỉ khi thư mục có đúng MỘT file .js; nhiều
                    // file mà không file nào khớp id thì bỏ qua nguồn thay
                    // vì đoán (thứ tự listFiles() không ổn định).
                    ?: sub.listFiles()?.filter { it.name.endsWith(".js") }?.singleOrNull()
                    ?: return@runCatching
                val domainsArr = o.optJSONArray("domains")
                val domains = if (domainsArr == null) listOf(id) else
                    (0 until domainsArr.length()).map { domainsArr.getString(it).lowercase() }
                val caps = parseCapabilities(o)
                out += Source(
                    id = id,
                    name = o.optString("name", id),
                    domains = domains,
                    useWebview = o.optBoolean("use_webview", false),
                    useHtmlParser = o.optBoolean("use_html_parser", true),
                    mayNeedVpn = o.optBoolean("may_need_vpn", false),
                    capabilities = caps,
                    version = o.optInt("version", 4),
                    engine = o.optInt("engine", catalogEngine.takeIf { it > 0 } ?: 4),
                    homepage = o.optString("homepage_url", ""),
                    logo = o.optString("logo", ""),
                    scriptFile = script,
                    scriptAssetPath = null,
                )
            }
        }
        publishSources(out)
    }

    /**
     * catalog.json already contains the routing metadata needed at runtime.
     * Reading it once replaces 74 manifest opens; each ~50 KB script is opened
     * only when its domain is actually used.
     */
    private fun reloadAssets() {
        val context = assetContext ?: return
        val root = assetsRoot ?: return
        val catalog = runCatching {
            context.assets.open("$root/catalog.json").bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
        }.getOrNull() ?: run {
            publishSources(emptyList())
            return
        }
        catalogEngine = catalog.optInt("engine", 4)
        val array = catalog.optJSONArray("sources") ?: JSONArray()
        val out = ArrayList<Source>(array.length())
        for (index in 0 until array.length()) {
            val o = array.optJSONObject(index) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            val domainsArray = o.optJSONArray("domains")
            val domains = if (domainsArray == null) listOf(id) else
                (0 until domainsArray.length()).mapNotNull {
                    domainsArray.optString(it).trim().lowercase().takeIf(String::isNotBlank)
                }
            out += Source(
                id = id,
                name = o.optString("name", id),
                domains = domains,
                useWebview = o.optBoolean("use_webview", false),
                useHtmlParser = o.optBoolean("use_html_parser", true),
                mayNeedVpn = o.optBoolean("may_need_vpn", false),
                capabilities = parseCapabilities(o),
                version = o.optInt("version", 4),
                engine = o.optInt("engine", catalogEngine.takeIf { it > 0 } ?: 4),
                homepage = o.optString("homepage_url", ""),
                logo = o.optString("logo", ""),
                scriptFile = null,
                scriptAssetPath = "$root/sources/$id/$id.js",
            )
        }
        publishSources(out)
    }

    private fun publishSources(value: List<Source>) {
        sources = value
        val map = LinkedHashMap<String, Source>()
        value.forEach { source ->
            source.domains.forEach { domain ->
                val normalized = domain.trim().trimStart('.').lowercase()
                if (normalized.isNotBlank()) map.putIfAbsent(normalized, source)
            }
        }
        sourceByDomain = map
        cachedDomains = map.keys.toList()
        JsSourceEngine.invalidateCache()
    }

    fun sourceCount(): Int = sources.size
    fun engineVersion(): Int = sources.maxOfOrNull { it.engine } ?: catalogEngine
    fun allSources(): List<Source> = sources
    fun allDomains(): List<String> = cachedDomains
    fun findForHost(host: String): Source? {
        val normalizedHost = host.trim().lowercase()
        return sourceByDomain[normalizedHost]
            // Trong các nguồn khớp theo hậu tố, chọn nguồn có domain khớp
            // DÀI nhất (cụ thể nhất) thay vì nguồn đầu tiên trong danh sách.
            ?: sources.mapNotNull { src -> src.matchLength(normalizedHost).takeIf { it >= 0 }?.let { src to it } }
                .maxByOrNull { it.second }?.first
            // truyendich.space is a mirror/renamed host of the same TruyenDich.AI
            // page structure. Keep this fallback so downloaded parser catalogs
            // can still route it even if their domain list has not been refreshed.
            ?: if (normalizedHost == "truyendich.space" ||
                normalizedHost.endsWith(".truyendich.space")
            ) {
                sources.firstOrNull { it.id == "truyendich.store" }
            } else {
                null
            }
    }

    fun findForUrl(url: String): Source? {
        val host = try {
            (URI(url).host ?: "").lowercase()
        } catch (_: Exception) {
            ""
        }
        if (host.isBlank()) return null
        return findForHost(host)
    }
}
