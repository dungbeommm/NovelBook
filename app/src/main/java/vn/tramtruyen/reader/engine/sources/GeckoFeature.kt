package vn.tramtruyen.reader.engine.sources

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.tramtruyen.reader.SourceBrowserActivity
import vn.tramtruyen.reader.TramTruyenApplication
import vn.tramtruyen.reader.engine.Config

interface GeckoChapterLoader {
    // Null means the browser could not initialize. Page failures are reported to the caller.
    suspend fun fetch(context: Context, url: String): String?
}

object GeckoFeature {
    private const val BROWSER = "vn.tramtruyen.reader.gecko.GeckoBrowserActivity"
    private const val LOADER = "vn.tramtruyen.reader.gecko.GeckoLoader"

    fun supports(url: String): Boolean = SangTacVietChapter.supportsSite(url)
    fun isChapter(url: String): Boolean = SangTacVietChapter.matches(url)

    private fun guideRequiresGecko(url: String): Boolean =
        supports(url) && SiteConfigStore.findForUrl(url)?.needGecko == true

    private fun installed(context: Context): Boolean =
        runCatching { Class.forName(BROWSER, false, context.classLoader) }.isSuccess

    /** Man Cai dat dung khoa nay de biet ban dang chay da co Gecko hay chua. */
    fun isInstalled(context: Context): Boolean = installed(context)

    suspend fun open(activity: Activity, url: String): Boolean {
        val required = withContext(Dispatchers.IO) {
            TramTruyenApplication.awaitReady()
            guideRequiresGecko(url)
        }
        if (!required || !installed(activity) && !install(activity)) return false
        return runCatching {
            activity.startActivity(Intent().setClassName(activity, BROWSER)
                .putExtra(SourceBrowserActivity.EXTRA_URL, url))
        }.isSuccess
    }

    private suspend fun install(activity: Activity): Boolean = try {
        GeckoModuleInstaller.install(activity)
        installed(activity)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    internal suspend fun fetchIfInstalled(url: String): String? {
        if (!guideRequiresGecko(url)) return null
        val context = Config.appContext ?: return null
        val loader = runCatching {
            Class.forName(LOADER, true, context.classLoader).getDeclaredConstructor().newInstance() as GeckoChapterLoader
        }.getOrNull() ?: return null
        return try {
            loader.fetch(context, url)
        } catch (_: LinkageError) {
            null
        }
    }
}
