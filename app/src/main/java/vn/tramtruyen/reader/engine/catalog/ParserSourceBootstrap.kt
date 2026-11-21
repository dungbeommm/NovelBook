package vn.tramtruyen.reader.engine.catalog

import android.content.Context
import java.io.File

/**
 * Registers parsers directly from APK assets.
 *
 * Older builds copied all 74 scripts (~4.2 MB) to filesDir on every app update.
 * Asset-backed, lazy script loading removes that cold-start write amplification
 * and also avoids keeping two copies of the parser bundle on the device.
 */
object ParserSourceBootstrap {
    private const val ASSET_DIR = "parser_v4"

    fun init(context: Context) {
        val appContext = context.applicationContext
        val downloadedRoot = File(appContext.filesDir, "web_parser/$ASSET_DIR")
        if (File(downloadedRoot, "catalog.json").isFile) {
            ParserSourceStore.init(downloadedRoot)
        } else {
            ParserSourceStore.initAssets(appContext, ASSET_DIR)
        }
        // Clean the obsolete copied bundle once. This is already running on the
        // core background thread, so deletion never blocks the first UI frame.
        runCatching { java.io.File(appContext.filesDir, "parser_repo").deleteRecursively() }
    }
}
