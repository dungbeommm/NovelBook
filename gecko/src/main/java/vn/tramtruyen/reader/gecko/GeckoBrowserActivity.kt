package vn.tramtruyen.reader.gecko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.splitcompat.SplitCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import vn.tramtruyen.reader.SourceBrowserActivity
import vn.tramtruyen.reader.engine.ReadingEngine
import vn.tramtruyen.reader.engine.sources.BrowserHistoryManager
import vn.tramtruyen.reader.engine.sources.BrowserSession
import vn.tramtruyen.reader.engine.sources.GeckoFeature

class GeckoBrowserActivity : SourceBrowserActivity() {
    private var page: GeckoPage? = null
    private var web: GeckoView? = null
    private var container: FrameLayout? = null
    private var createJob: Job? = null
    private var pendingUrl = ""
    private var desktopMode = false
    private var openingWebView = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        SplitCompat.installActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingUrl = savedInstanceState?.getString(EXTRA_URL)
            ?: intent.getStringExtra(EXTRA_URL).orEmpty()
        desktopMode = savedInstanceState?.getBoolean(STATE_DESKTOP) ?: BrowserSession.isDesktopMode()
        super.onCreate(savedInstanceState)
        if (!GeckoFeature.supports(pendingUrl)) finish()
    }

    override fun attachBrowser(container: FrameLayout) {
        this.container = container
        web?.let {
            attachView(it)
            return
        }
        if (isFinishing || createJob?.isActive == true) return

        createJob = lifecycleScope.launch {
            try {
                val browsing = GeckoPage.create(this@GeckoBrowserActivity)
                page = browsing
                browsing.onLocationChanged = { url ->
                    onUrlChanged(url)
                    onTitleChanged("")
                    BrowserHistoryManager.record(url = url, title = "")
                }
                browsing.onTitleChanged = { title ->
                    onTitleChanged(title)
                    BrowserHistoryManager.record(url = browsing.url, title = title)
                }
                browsing.onNavigationChanged = ::onNavigationChanged
                browsing.onProgressChanged = ::onProgressChanged
                browsing.onCrash = { openWebView(browserUrl(), webViewOnly = true) }
                applyDesktopMode(browsing.session)
                val view = GeckoView(this@GeckoBrowserActivity)
                web = view
                view.setSession(browsing.session)
                attachView(view)
                browsing.session.loadUri(pendingUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                openWebView(browserUrl(), webViewOnly = true)
            } catch (_: LinkageError) {
                openWebView(browserUrl(), webViewOnly = true)
            }
        }
    }

    private fun attachView(view: GeckoView) {
        val target = container ?: return
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    override fun detachBrowser() {
        (web?.parent as? ViewGroup)?.removeView(web)
        container = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_URL, browserUrl().takeIf(GeckoFeature::supports) ?: pendingUrl)
        outState.putBoolean(STATE_DESKTOP, desktopMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        createJob?.cancel()
        web?.releaseSession()
        page?.close()
        web = null
        page = null
        super.onDestroy()
    }

    override fun onUserNavigation(url: String): Boolean = false

    override fun browserUrl(): String = page?.url?.ifBlank { pendingUrl } ?: pendingUrl

    override fun navigatePage(input: String) {
        val target = BrowserSession.normalizeInput(input)
        if (GeckoFeature.supports(target)) {
            pendingUrl = target
            onUrlChanged(target)
            page?.session?.loadUri(target)
        } else {
            openWebView(target)
        }
    }

    override fun openBrowserHome() = openWebView(BrowserSession.HOME_URL)

    override fun goBackPage(): Boolean {
        val active = page ?: return false
        if (!active.canGoBack) return false
        active.session.goBack()
        return true
    }

    override fun goForwardPage() { page?.session?.goForward() }
    override fun reloadPage() { page?.session?.reload() }
    override fun isDesktopPage() = desktopMode

    override fun setDesktopPage(enabled: Boolean) {
        desktopMode = enabled
        page?.session?.let {
            applyDesktopMode(it)
            it.reload()
        }
    }

    private fun applyDesktopMode(session: GeckoSession) {
        session.settings.userAgentMode = if (desktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (desktopMode) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
    }

    override fun findOnPage(query: String) {
        val finder = page?.session?.finder ?: return
        if (query.isBlank()) finder.clear() else finder.find(query, GeckoSession.FINDER_FIND_FORWARD)
    }

    override fun findNextOnPage(forward: Boolean) {
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        page?.session?.finder?.find(null, flags)
    }

    override suspend fun readBrowserChapter(url: String): String? {
        check(GeckoFeature.isChapter(url)) { "Hãy mở một chương Sáng Tác Việt trước." }
        val browsing = page ?: return null
        return try {
            val (html, generation) = browsing.awaitChapter(url, 8_000L, currentOnly = true)
            val chapter = withContext(Dispatchers.Default) {
                ReadingEngine.chapterFromBrowser(url, html)
            }
            check(browsing.isCurrent(url, generation)) { "Trang đã thay đổi. Hãy bấm nghe lại." }
            chapter
        } catch (_: GeckoUnavailable) {
            openWebView(url, webViewOnly = true)
            null
        }
    }

    private fun openWebView(url: String, webViewOnly: Boolean = false) {
        if (openingWebView || isFinishing) return
        openingWebView = true
        startActivity(Intent(this, SourceBrowserActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_WEBVIEW_ONLY, webViewOnly))
        finish()
    }

    private companion object {
        const val STATE_DESKTOP = "gecko_browser_desktop"
    }
}
