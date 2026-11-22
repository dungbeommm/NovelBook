package vn.tramtruyen.reader.engine

import java.io.File

/**
 * Cau hinh runtime — tuong duong config.py cua ban Python.
 * Thu muc ghi du lieu do TramTruyenApplication truyen vao luc khoi dong.
 */
object Config {
    // Tai truyen
    const val MAX_WORKERS = 3
    const val MIN_DELAY = 1.5
    const val MAX_DELAY = 4.0
    const val MAX_RETRIES = 3
    const val RETRY_BACKOFF = 2.0
    const val REQUEST_TIMEOUT_SEC = 30L

    const val MAX_FOLDER_NAME = 120
    const val MAX_FILE_NAME = 100

    // Thu muc ghi du lieu (set luc khoi dong).
    @Volatile lateinit var dataDir: File

    var proxy: String? = null

    // Context ung dung (set luc khoi dong) — can cho WebViewFetcher tao WebView an
    // va cho SiteConfigStore doc parser.json trong assets.
    @Volatile var appContext: android.content.Context? = null

    // TTS API keys (tuy chon — mac dinh rong => provider cloud khong bat).
    var geminiApiKey: String = ""
    var zaloApiKey: String = ""

    fun init(dataDir: File) {
        this.dataDir = dataDir.apply { mkdirs() }
    }
}
