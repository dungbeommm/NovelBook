package vn.tramtruyen.reader.gecko

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.network.AppDns
import vn.tramtruyen.reader.engine.sources.GeckoChapterLoader
import vn.tramtruyen.reader.engine.sources.GeckoFeature

internal class GeckoUnavailable(cause: Throwable? = null) : RuntimeException(cause)

private object GeckoRuntimeHolder {
    private var runtime: GeckoRuntime? = null
    private var extension: GeckoResult<WebExtension>? = null
    private var dnsProvider: AppDns.Provider? = null

    fun runtime(context: Context): GeckoRuntime {
        val active = runtime ?: GeckoRuntime.create(runtimeContext(context)).also { runtime = it }
        val selected = AppDns.provider.value
        if (selected != dnsProvider) {
            active.settings.setDohAutoselectEnabled(false)
            val endpoint = selected.endpoint
            if (endpoint == null) {
                active.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_DISABLED)
            } else {
                active.settings.setTrustedRecursiveResolverUri(endpoint)
                active.settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST)
            }
            dnsProvider = selected
        }
        return active
    }

    private fun runtimeContext(context: Context): Context {
        val app = context.applicationContext
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val names: Array<out String>? = packageInfo.splitNames
        val index = names?.indexOf("gecko") ?: -1
        val geckoApk = packageInfo.applicationInfo?.splitSourceDirs?.getOrNull(index) ?: return app
        // Gecko reads omni.ja and its built-in extension from the runtime APK.
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageResourcePath(): String = geckoApk
        }
    }

    suspend fun extension(runtime: GeckoRuntime): WebExtension {
        val result = extension ?: runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/stv_reader/", "stv-reader@tramtruyen.vn",
        ).also { extension = it }
        val ready = CompletableDeferred<WebExtension>()
        result.accept({ installed ->
            if (installed != null) ready.complete(installed)
            else ready.completeExceptionally(GeckoUnavailable())
        }, { ready.completeExceptionally(GeckoUnavailable(it)) })
        return withTimeoutOrNull(25_000L) { ready.await() }
            ?: throw GeckoUnavailable(IllegalStateException("Hết thời gian khởi tạo Gecko"))
    }
}

internal class GeckoPage private constructor(
    val session: GeckoSession,
    private val extension: WebExtension,
) {
    var url = ""
        private set
    var canGoBack = false
        private set
    private var canGoForward = false
    var onLocationChanged: (String) -> Unit = {}
    var onNavigationChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
    var onProgressChanged: (Int) -> Unit = {}
    var onTitleChanged: (String) -> Unit = {}
    var onCrash: () -> Unit = {}
    private var crashed = false
    private var loadFailed = false
    private var generation = 0
    private var port: WebExtension.Port? = null
    private var nextId = 0
    private var reply: Pair<Int, CompletableDeferred<JSONObject>>? = null
    private val readLock = Mutex()

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                val current = url.orEmpty()
                if (current != this@GeckoPage.url) generation++
                this@GeckoPage.url = current
                onLocationChanged(current)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoPage.canGoBack = canGoBack
                onNavigationChanged(canGoBack, canGoForward)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@GeckoPage.canGoForward = canGoForward
                onNavigationChanged(canGoBack, canGoForward)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                loadFailed = true
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                generation++
                loadFailed = false
                port = null
                onProgressChanged(0)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) = onProgressChanged(progress)
            override fun onPageStop(session: GeckoSession, success: Boolean) = onProgressChanged(100)
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) = onTitleChanged(title.orEmpty())
            override fun onCrash(session: GeckoSession) = rendererClosed()
            override fun onKill(session: GeckoSession) = rendererClosed()
        }
        session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val sender = port.sender
                if (!sender.isTopLevel || sender.session !== session || !GeckoFeature.supports(sender.url)) {
                    port.disconnect()
                    return
                }
                this@GeckoPage.port = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val response = message as? JSONObject ?: return
                        val pending = reply ?: return
                        if (this@GeckoPage.port === port && response.optInt("id", -1) == pending.first) {
                            pending.second.complete(response)
                        }
                    }

                    override fun onDisconnect(port: WebExtension.Port) {
                        if (this@GeckoPage.port === port) this@GeckoPage.port = null
                    }
                })
            }
        }, "stvChapter")
    }

    private fun rendererClosed() {
        crashed = true
        onCrash()
    }

    suspend fun awaitChapter(expected: String, timeoutMs: Long, currentOnly: Boolean): Pair<String, Int> =
        readLock.withLock {
            val originalGeneration = generation
            withTimeoutOrNull(timeoutMs) {
                var html = ""
                var capturedGeneration = generation
                while (html.isBlank()) {
                    if (crashed) throw GeckoUnavailable()
                    if (loadFailed) throw ApiError(422, "Gecko không mở được trang Sáng Tác Việt.")
                    if (currentOnly && !isCurrent(expected, originalGeneration)) {
                        throw ApiError(422, "Trang đã thay đổi. Hãy bấm nghe lại trên chương đang mở.")
                    }
                    val currentPort = port
                    if (currentPort != null && url == expected) {
                        val ticket = ++nextId
                        val waiting = CompletableDeferred<JSONObject>()
                        val requestedGeneration = generation
                        reply = ticket to waiting
                        val result = try {
                            currentPort.postMessage(JSONObject().put("type", "read").put("id", ticket).put("url", expected))
                            withTimeoutOrNull(700L) { waiting.await() }
                        } finally {
                            reply = null
                        }
                        if (isCurrent(expected, requestedGeneration)) {
                            when (result?.optString("state")) {
                                "blocked" -> throw ApiError(429, "Hãy xác minh trên trang Sáng Tác Việt rồi thử lại.")
                                "ready" -> {
                                    html = result.optString("html")
                                    capturedGeneration = requestedGeneration
                                }
                            }
                        }
                    }
                    if (html.isBlank()) delay(350L)
                }
                html to capturedGeneration
            } ?: throw ApiError(422, "Hãy mở chương trên Sáng Tác Việt và đợi trang hiển thị văn bản.")
        }

    fun isCurrent(expected: String, expectedGeneration: Int): Boolean =
        !crashed && expected == url && expectedGeneration == generation

    fun close() {
        port?.disconnect()
        onCrash = {}
        session.webExtensionController.setMessageDelegate(extension, null, "stvChapter")
        if (session.isOpen) session.close()
    }

    companion object {
        private var initializationFailed = false

        suspend fun create(context: Context): GeckoPage {
            if (initializationFailed) throw GeckoUnavailable()
            val session = GeckoSession()
            try {
                val runtime = GeckoRuntimeHolder.runtime(context)
                val chapterExtension = GeckoRuntimeHolder.extension(runtime)
                GeckoAdBlock.apply(runtime)
                val page = GeckoPage(session, chapterExtension)
                session.open(runtime)
                return page
            } catch (cancelled: CancellationException) {
                if (session.isOpen) session.close()
                throw cancelled
            } catch (exception: Throwable) {
                if (exception !is Exception && exception !is LinkageError) throw exception
                Log.w("TramTruyenGecko", "Unable to initialize Gecko", exception)
                initializationFailed = true
                if (session.isOpen) session.close()
                throw GeckoUnavailable(exception)
            }
        }
    }
}

@Keep
class GeckoLoader : GeckoChapterLoader {
    override suspend fun fetch(context: Context, url: String): String? = withContext(Dispatchers.Main) {
        try {
            val page = GeckoPage.create(context)
            try {
                page.session.setActive(true)
                page.session.loadUri(url)
                page.awaitChapter(url, 18_000L, currentOnly = false).first
            } finally {
                page.close()
            }
        } catch (_: GeckoUnavailable) {
            null
        }
    }
}
