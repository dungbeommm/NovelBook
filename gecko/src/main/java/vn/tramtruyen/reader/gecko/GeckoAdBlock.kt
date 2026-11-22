package vn.tramtruyen.reader.gecko

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController.EnableSource
import vn.tramtruyen.reader.engine.sources.AdBlockManager

internal object GeckoAdBlock {
    private val lock = Mutex()
    private var extension: WebExtension? = null
    private var appliedEnabled: Boolean? = null

    suspend fun apply(runtime: GeckoRuntime) = lock.withLock {
        val enabled = AdBlockManager.state.value.enabled
        if (appliedEnabled == enabled) return@withLock
        val controller = runtime.webExtensionController
        try {
            val current = extension ?: controller.ensureBuiltIn(
                "resource://android/assets/tramtruyen_adblock/", "adblock@tramtruyen.vn",
            ).awaitExtension().also { extension = it }
            if (current.metaData?.enabled != enabled) {
                extension = if (enabled) {
                    controller.enable(current, EnableSource.APP).awaitExtension()
                } else {
                    controller.disable(current, EnableSource.APP).awaitExtension()
                }
            }
            appliedEnabled = enabled
            Log.d("TramTruyenGecko", "Ad blocker enabled: $enabled")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("TramTruyenGecko", "Unable to configure ad blocking", error)
        }
    }

    private suspend fun GeckoResult<WebExtension>.awaitExtension(): WebExtension {
        val result = CompletableDeferred<WebExtension>()
        accept({ extension ->
            if (extension != null) result.complete(extension)
            else result.completeExceptionally(IllegalStateException("No ad-block extension returned"))
        }, { error ->
            result.completeExceptionally(error ?: IllegalStateException("Ad-block extension operation failed"))
        })
        return checkNotNull(withTimeoutOrNull(12_000L) { result.await() }) {
            "Timed out while configuring ad blocking"
        }
    }
}
