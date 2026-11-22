package vn.tramtruyen.reader.engine.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import vn.tramtruyen.reader.engine.Config
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 1 diem proxy (cong dong hoac nhap tay). */
data class ProxyEndpoint(
    val type: Proxy.Type,   // HTTP hoac SOCKS
    val host: String,
    val port: Int,
) {
    fun toJavaProxy(): Proxy = Proxy(type, InetSocketAddress(host, port))
    /** Rule cho WebView ProxyConfig: "scheme://host:port". */
    fun toWebViewRule(): String =
        (if (type == Proxy.Type.SOCKS) "socks5" else "http") + "://" + host + ":" + port
    override fun toString(): String =
        (if (type == Proxy.Type.SOCKS) "socks5" else "http") + "://" + host + ":" + port
}

/**
 * Trung tam quan ly PROXY — "VPN" cho scraper.
 *
 * - Quyet dinh CO dung proxy khong (theo che do off / flagged / all).
 * - Giu proxy dang dung (đa kiem tra song).
 * - Cap proxy cho OkHttp (javaProxy) va WebView (setProxyOverride, dem tham chieu).
 * - Chi ho tro proxy thu cong va mac dinh tat; khong gui du lieu qua proxy cong cong.
 * - Luu cau hinh o dataDir/proxy.json.
 */
object ProxyManager {
    private const val TAG = "ProxyManager"

    // che do ap dung: "off" | "flagged" (chi site may_need_vpn) | "all"
    @Volatile var mode: String = "off"; private set
    // Chi con nguon manual.
    @Volatile var source: String = "manual"; private set
    @Volatile var enabled: Boolean = false; private set
    @Volatile private var manual: ProxyEndpoint? = null

    // proxy dang dung (da test song). null = chua chon.
    @Volatile var current: ProxyEndpoint? = null; private set

    private val webviewRef = AtomicInteger(0)
    private val mainExecutor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }

    /** Co nen dung proxy cho request nay khong. */
    fun shouldUseProxy(mayNeedVpn: Boolean): Boolean {
        if (!enabled) return false
        return when (mode) {
            "off" -> false
            "all" -> true
            else -> mayNeedVpn // "flagged"
        }
    }

    /** Dam bao co 1 proxy song. Tra ve endpoint hoac null neu khong tim duoc. */
    suspend fun ensureProxy(): ProxyEndpoint? {
        current?.let { return it }
        val picked = manual
        current = picked
        if (picked != null) Log.i(TAG, "Dung proxy: $picked") else Log.w(TAG, "Khong tim duoc proxy song")
        return picked
    }

    fun javaProxy(): Proxy? = current?.toJavaProxy()

    /** Danh dau proxy hien tai hong -> lan sau chon cai khac. */
    fun markBad() {
        current = null
    }

    fun reset() { current = null }

    // ---- WebView proxy override (process-wide, dem tham chieu) ----
    suspend fun acquireWebViewProxy(): Boolean {
        val ep = current ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        webviewRef.incrementAndGet()
        return setOverride(ep.toWebViewRule())
    }

    suspend fun releaseWebViewProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        if (webviewRef.decrementAndGet() <= 0) {
            webviewRef.set(0)
            clearOverride()
        }
    }

    private suspend fun setOverride(rule: String): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .addDirect() // neu proxy loi thi di truc tiep (con hon khong tai duoc)
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, mainExecutor) {
                    if (cont.isActive) cont.resume(true)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    private suspend fun clearOverride() =
        suspendCancellableCoroutine<Unit> { cont ->
            try {
                ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                    if (cont.isActive) cont.resume(Unit)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }

    // ---- luu / doc cau hinh ----
    fun load() {
        try {
            val f = File(Config.dataDir, "proxy.json")
            if (!f.exists()) { save(); return }
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false
            mode = (o["mode"] as? JsonPrimitive)?.contentOrNull ?: "off"
            source = "manual"
            val mo = o["manual"] as? JsonObject
            if (mo != null) {
                val host = (mo["host"] as? JsonPrimitive)?.contentOrNull ?: ""
                val port = (mo["port"] as? JsonPrimitive)?.intOrNull ?: 0
                val t = (mo["type"] as? JsonPrimitive)?.contentOrNull ?: "http"
                if (host.isNotBlank() && port > 0)
                    manual = ProxyEndpoint(if (t.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP, host, port)
            }
        } catch (e: Exception) { Log.e(TAG, "load proxy.json: ${e.message}") }
    }

    fun save() {
        try {
            val o = buildJsonObject {
                put("enabled", enabled); put("mode", mode); put("source", source)
                manual?.let { m ->
                    putJsonObject("manual") {
                        put("type", if (m.type == Proxy.Type.SOCKS) "socks5" else "http")
                        put("host", m.host); put("port", m.port)
                    }
                }
            }
            File(Config.dataDir, "proxy.json").writeText(o.toString())
        } catch (e: Exception) { Log.e(TAG, "save proxy.json: ${e.message}") }
    }

    fun configure(enabled: Boolean?, mode: String?, source: String?, manual: ProxyEndpoint?) {
        if (enabled != null) this.enabled = enabled
        if (mode != null) this.mode = mode
        if (source != null) this.source = source
        if (manual != null) this.manual = manual
        current = null
        save()
    }

    fun statusJson(): JsonObject = buildJsonObject {
        put("enabled", enabled); put("mode", mode); put("source", source)
        put("current", current?.toString() ?: "")
        put("manual", manual?.toString() ?: "")
        put("webview_proxy_supported", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
    }
}
