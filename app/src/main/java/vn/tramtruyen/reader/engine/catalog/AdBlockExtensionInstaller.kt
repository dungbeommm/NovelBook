package vn.tramtruyen.reader.engine.catalog

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import vn.tramtruyen.reader.R
import vn.tramtruyen.reader.engine.network.AppDns
import vn.tramtruyen.reader.engine.sources.AdBlockManager
import vn.tramtruyen.reader.engine.sources.BrowserSession
import kotlin.coroutines.coroutineContext

/** Downloads and activates the standalone ad-block extension bundle. */
object AdBlockExtensionInstaller {
    private const val FORMAT = "tramtruyen-adblock-extension"
    private const val PACKAGE_DIR = "adblock_extension"
    private const val MAX_DOWNLOAD_BYTES = 4L * 1024 * 1024
    private val REQUIRED_FILES = listOf(
        "adblock_rules.json",
        "adblock_cosmetic.js",
        "browser_ad_guard.js",
    )
    private val installLock = Mutex()
    private val client = AppDns.clientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class InstalledInfo(val version: String, val ruleCount: Int)

    fun installedInfo(context: Context): InstalledInfo? {
        val file = File(context.applicationContext.filesDir, "$PACKAGE_DIR/manifest.json")
        if (!file.isFile) return null
        return runCatching {
            val manifest = JSONObject(file.readText(Charsets.UTF_8))
            val version = manifest.optString("package_version")
            val count = manifest.optInt("rule_count")
            if (version.isBlank() || count <= 0) null else InstalledInfo(version, count)
        }.getOrNull()
    }

    suspend fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit = {}) =
        installLock.withLock {
            val app = context.applicationContext
            val download = File.createTempFile("adblock-extension-", ".json", app.cacheDir)
            try {
                download(app.getString(R.string.adblock_extension_download_url), download, onProgress)
                installJson(app, download)
                withContext(Dispatchers.Main.immediate) { onProgress(100) }
            } finally {
                download.delete()
            }
        }

    private suspend fun download(url: String, target: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                check(response.code != 404) { "Chưa có AdBlockExtension.json trên máy chủ (HTTP 404)." }
                check(response.isSuccessful) { "Không tải được extension chặn quảng cáo (HTTP ${response.code})." }
                val body = checkNotNull(response.body) { "Máy chủ trả về gói chặn quảng cáo rỗng." }
                val length = body.contentLength()
                check(length <= MAX_DOWNLOAD_BYTES) { "Gói chặn quảng cáo vượt giới hạn 4 MB." }
                var downloaded = 0L
                var lastProgress = -2
                body.byteStream().use { input ->
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= MAX_DOWNLOAD_BYTES) { "Gói chặn quảng cáo vượt giới hạn 4 MB." }
                            output.write(buffer, 0, count)
                            val progress = if (length > 0) (downloaded * 100 / length).toInt().coerceAtMost(100) else -1
                            if (progress != lastProgress) {
                                withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                                lastProgress = progress
                            }
                        }
                    }
                }
                check(downloaded > 0L && (length < 0L || downloaded == length)) {
                    "Tải gói chặn quảng cáo chưa hoàn tất."
                }
            }
        }

    private suspend fun installJson(context: Context, source: File) = withContext(Dispatchers.IO) {
        val root = JSONObject(source.readText(Charsets.UTF_8))
        check(root.optString("format") == FORMAT) { "Tệp tải về không phải extension chặn quảng cáo của NovelBook." }
        val version = root.optString("package_version").trim()
        check(version.isNotBlank()) { "Extension chặn quảng cáo thiếu số phiên bản." }
        val files = root.optJSONObject("files") ?: error("Extension chặn quảng cáo thiếu các tệp thành phần.")

        val contents = REQUIRED_FILES.associateWith { name ->
            val entry = files.optJSONObject(name) ?: error("Extension chặn quảng cáo thiếu $name.")
            val content = entry.optString("content")
            val expectedHash = entry.optString("sha256").lowercase()
            check(content.isNotBlank()) { "Tệp $name trong extension đang rỗng." }
            check(expectedHash.matches(Regex("[0-9a-f]{64}")) && sha256(content) == expectedHash) {
                "Kiểm tra dữ liệu $name trong extension thất bại."
            }
            content
        }
        val rules = JSONObject(contents.getValue("adblock_rules.json"))
        check(rules.optString("format") == "tramtruyen-adblock-rules-v1") {
            "Định dạng luật chặn quảng cáo không được hỗ trợ."
        }
        val hosts = rules.optJSONArray("blocked_hosts") ?: error("Danh sách host chặn quảng cáo không hợp lệ.")
        val fragments = rules.optJSONArray("url_fragments") ?: error("Danh sách URL chặn quảng cáo không hợp lệ.")
        val queryKeys = rules.optJSONArray("query_keys") ?: error("Danh sách tham số URL chặn quảng cáo không hợp lệ.")
        val ruleCount = hosts.length() + fragments.length() + queryKeys.length()
        check(ruleCount > 0) { "Extension chặn quảng cáo không có quy tắc." }
        root.optJSONObject("counts")?.let { counts ->
            check(counts.optInt("rule_count") == ruleCount) {
                "Số quy tắc trong manifest extension không khớp."
            }
        }

        val parent = context.filesDir
        val stage = File(parent, "$PACKAGE_DIR.staging")
        val target = File(parent, PACKAGE_DIR)
        val backup = File(parent, "$PACKAGE_DIR.previous")
        stage.deleteRecursively()
        check(stage.mkdirs() || stage.isDirectory) { "Không tạo được thư mục extension chặn quảng cáo." }
        contents.forEach { (name, content) -> File(stage, name).writeText(content, Charsets.UTF_8) }
        File(stage, "manifest.json").writeText(
            JSONObject()
                .put("package_version", version)
                .put("rules_format", "tramtruyen-adblock-rules-v1")
                .put("rule_count", ruleCount)
                .toString(2),
            Charsets.UTF_8,
        )

        backup.deleteRecursively()
        if (target.exists()) check(target.renameTo(backup)) { "Không thể thay thế extension chặn quảng cáo hiện tại." }
        if (!stage.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("Không thể kích hoạt extension chặn quảng cáo mới.")
        }
        backup.deleteRecursively()

        AdBlockManager.reload(context)
        BrowserSession.invalidateAdBlockExtensionScripts()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
