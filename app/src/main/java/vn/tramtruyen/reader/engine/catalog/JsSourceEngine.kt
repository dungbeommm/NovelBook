package vn.tramtruyen.reader.engine.catalog

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import vn.tramtruyen.reader.engine.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicReference

/**
 * May chay ma nguon JavaScript engine v4 (parser source repository).
 *
 * Moi file sources/[mien]/[mien].js dinh nghia `ReaderSource.execute(action, payload)`.
 * Engine v4 co the tra ve san chuoi JSON (JSON.stringify) — bo chay xu ly ca 2 dang.
 *
 * Dung WebView an, khong mang, chi de chay JS + DOMParser.
 */
object JsSourceEngine {

    private const val TAG = "JsSourceEngine"
    private const val EXEC_TIMEOUT_MS = 25_000L

    private val main = Handler(Looper.getMainLooper())
    private val scriptCache = HashMap<String, String>()

    fun invalidateCache() = synchronized(scriptCache) { scriptCache.clear() }

    private fun scriptOf(source: ParserSourceStore.Source): String = synchronized(scriptCache) {
        val key = source.scriptCacheKey()
        scriptCache[key] ?: source.readScript().also {
            // Chi giu 1 script gan nhat de tiet kiem RAM (moi file ~40KB, 74 source).
            scriptCache.clear()
            scriptCache[key] = it
        }
    }

    suspend fun execute(
        source: ParserSourceStore.Source,
        action: String,
        payload: JSONObject,
    ): JSONObject? {
        val script = withContext(Dispatchers.IO) {
            runCatching { scriptOf(source) }.getOrNull()
        } ?: return null

        val baseUrl = when {
            source.homepage.isNotBlank() -> source.homepage
            else -> "https://" + source.id + "/"
        }
        val raw = evaluate(script, action, payload, baseUrl) ?: return null
        return parseResult(raw)
    }

    private fun parseResult(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return null
        return runCatching {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONObject().put("items", JSONArray(text))
                text.startsWith("\"") -> {
                    // Engine v4 execute() co the tra JSON.stringify -> chuoi JSON long.
                    val inner = JSONTokener(text).nextValue() as? String ?: return@runCatching null
                    when {
                        inner.startsWith("{") -> JSONObject(inner)
                        inner.startsWith("[") -> JSONObject().put("items", JSONArray(inner))
                        else -> JSONObject().put("value", inner)
                    }
                }
                else -> JSONObject().put("value", text)
            }
        }.getOrNull()?.also { obj ->
            if (obj.has("__error")) {
                Log.w(TAG, "source script error: ${obj.optString("__error")}")
            }
        }?.takeUnless { it.has("__error") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun evaluate(
        script: String,
        action: String,
        payload: JSONObject,
        baseUrl: String,
    ): String? {
        val ctx = Config.appContext ?: return null
        val done = CompletableDeferred<String?>()
        val activeView = AtomicReference<WebView?>(null)
        fun destroyView() {
            main.post { runCatching { activeView.getAndSet(null)?.destroy() } }
        }

        main.post {
            try {
                val web = WebView(ctx)
                activeView.set(web)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.blockNetworkLoads = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        val call = buildString {
                            append("(function(){try{")
                            append(script)
                            append("\n;var __a=").append(JSONObject.quote(action)).append(";")
                            append("var __p=").append(payload.toString()).append(";")
                            append("var __r=(typeof ReaderSource!=='undefined'&&ReaderSource.execute)")
                            append("?ReaderSource.execute(__a,__p):null;")
                            // Neu engine tra san string JSON thi giu nguyen; object thi stringify.
                            append("if(__r===undefined||__r===null)return 'null';")
                            append("if(typeof __r==='string')return __r;")
                            append("return JSON.stringify(__r);")
                            append("}catch(e){return JSON.stringify({__error:String(e&&e.message||e)});}})();")
                        }
                        v.evaluateJavascript(call) { value ->
                            done.complete(unquote(value))
                            destroyView()
                        }
                    }
                }
                web.loadDataWithBaseURL(
                    baseUrl,
                    "<!doctype html><html><head><meta charset=utf-8></head><body></body></html>",
                    "text/html",
                    "utf-8",
                    null,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "webview init failed", e)
                destroyView()
                done.complete(null)
            }
        }

        return try {
            withTimeoutOrNull(EXEC_TIMEOUT_MS) { done.await() }
        } finally {
            // Hủy WebView cả khi parser treo hoặc coroutine bị hủy; tránh giữ
            // renderer/context nền sau nhiều lần chuyển nguồn.
            destroyView()
        }
    }

    private fun unquote(value: String?): String? {
        if (value == null || value == "null") return null
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return runCatching { JSONTokener(value).nextValue() as? String }.getOrNull() ?: value
        }
        return value
    }
}
