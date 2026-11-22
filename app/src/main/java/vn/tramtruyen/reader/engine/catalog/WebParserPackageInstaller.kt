package vn.tramtruyen.reader.engine.catalog

import android.content.Context
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
import vn.tramtruyen.reader.engine.sources.SiteConfigStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** One-click web delivery for all site parsers and their legacy supplements. */
object WebParserPackageInstaller {
    private const val FORMAT = "tramtruyen-web-parser-bundle"
    private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
    private const val PACKAGE_DIR = "web_parser"
    private val installLock = Mutex()
    private val client = AppDns.clientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class InstalledInfo(val version: String, val sourceCount: Int)

    fun installedInfo(context: Context): InstalledInfo? {
        val file = File(context.applicationContext.filesDir, "$PACKAGE_DIR/manifest.json")
        if (!file.isFile) return null
        return runCatching {
            val manifest = JSONObject(file.readText(Charsets.UTF_8))
            val version = manifest.optString("package_version")
            val sources = manifest.optInt("parser_v4_sources")
            if (version.isBlank() || sources <= 0) null else InstalledInfo(version, sources)
        }.getOrNull()
    }

    suspend fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit = {}) =
        installLock.withLock {
            val app = context.applicationContext
            val download = File.createTempFile("web-parser-", ".json", app.cacheDir)
            try {
                download(app.getString(R.string.web_parser_bundle_download_url), download, onProgress)
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
                check(response.code != 404) { "Chưa có WebParser.json trên máy chủ (HTTP 404)." }
                check(response.isSuccessful) { "Không tải được bộ parser web (HTTP ${response.code})." }
                val body = checkNotNull(response.body) { "Máy chủ trả về tệp rỗng." }
                val length = body.contentLength()
                check(length <= MAX_DOWNLOAD_BYTES) { "WebParser.json vượt giới hạn 20 MB." }
                var downloaded = 0L
                var lastProgress = -2
                body.byteStream().use { input ->
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= MAX_DOWNLOAD_BYTES) { "WebParser.json vượt giới hạn 20 MB." }
                            output.write(buffer, 0, count)
                            val progress = if (length > 0) (downloaded * 100 / length).toInt().coerceAtMost(100) else -1
                            if (progress != lastProgress) {
                                withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                                lastProgress = progress
                            }
                        }
                    }
                }
                check(downloaded > 0L && (length < 0L || downloaded == length)) { "Tệp parser tải chưa đủ." }
            }
        }

    private suspend fun installJson(context: Context, source: File) = withContext(Dispatchers.IO) {
        val root = JSONObject(source.readText(Charsets.UTF_8))
        check(root.optString("format") == FORMAT) { "Tệp tải về không phải bộ parser NovelBook." }
        val packageVersion = root.optString("package_version").trim()
        check(packageVersion.isNotBlank()) { "Bộ parser thiếu số phiên bản." }

        val v4 = root.optJSONObject("parser_v4") ?: error("Bộ parser thiếu parser_v4.")
        val catalog = v4.optJSONObject("catalog") ?: error("Bộ parser thiếu catalog.")
        val sources = v4.optJSONObject("sources") ?: error("Bộ parser thiếu danh sách nguồn.")
        val catalogSources = catalog.optJSONArray("sources") ?: error("Catalog parser không hợp lệ.")
        check(catalogSources.length() > 0 && catalogSources.length() == sources.length()) {
            "Số parser trong catalog không khớp dữ liệu tải về."
        }
        val legacy = root.optJSONArray("legacy_parser") ?: error("Bộ parser thiếu cấu hình tương thích.")
        check(legacy.length() > 0) { "Bộ parser bị thiếu dữ liệu website." }

        val parent = context.filesDir
        val stage = File(parent, "$PACKAGE_DIR.staging")
        val target = File(parent, PACKAGE_DIR)
        val backup = File(parent, "$PACKAGE_DIR.previous")
        stage.deleteRecursively()
        val parserRoot = File(stage, "parser_v4")
        val sourceRoot = File(parserRoot, "sources")
        check(sourceRoot.mkdirs() || sourceRoot.isDirectory) { "Không tạo được thư mục parser." }
        writeJson(File(parserRoot, "catalog.json"), catalog)

        val ids = HashSet<String>()
        for (index in 0 until sources.length()) {
            val key = sources.names()?.optString(index).orEmpty()
            check(isSafeSegment(key) && ids.add(key)) { "Tên parser không an toàn hoặc bị trùng." }
            val item = sources.optJSONObject(key) ?: error("Parser $key không hợp lệ.")
            val directory = item.optString("directory")
            check(isSafeSegment(directory)) { "Thư mục parser $key không hợp lệ." }
            val manifest = item.optJSONObject("manifest") ?: error("Parser $key thiếu manifest.")
            val scriptName = item.optString("script_file")
            check(isSafeSegment(scriptName) && scriptName.endsWith(".js")) { "Tên script của $key không hợp lệ." }
            val script = item.optString("script")
            check(script.isNotBlank()) { "Script của parser $key đang rỗng." }
            val expectedSha = item.optString("script_sha256").lowercase()
            check(expectedSha.matches(Regex("[0-9a-f]{64}")) && sha256(script) == expectedSha) {
                "Kiểm tra dữ liệu parser $key thất bại."
            }
            val dir = File(sourceRoot, directory)
            check(dir.mkdirs() || dir.isDirectory) { "Không tạo được parser $key." }
            writeJson(File(dir, "manifest.json"), manifest)
            File(dir, scriptName).writeText(script, Charsets.UTF_8)
        }

        writeJson(File(stage, "parser.json"), legacy)
        val counts = root.optJSONObject("counts")
        val installedManifest = JSONObject()
            .put("package_version", packageVersion)
            .put("parser_v4_sources", catalogSources.length())
            .put("legacy_site_configs", legacy.length())
        counts?.let { check(it.optInt("parser_v4_sources") == catalogSources.length()) {
            "Số lượng parser trong manifest không khớp."
        } }
        writeJson(File(stage, "manifest.json"), installedManifest)

        backup.deleteRecursively()
        if (target.exists()) check(target.renameTo(backup)) { "Không thể thay thế bộ parser hiện tại." }
        if (!stage.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("Không thể kích hoạt bộ parser mới.")
        }
        backup.deleteRecursively()

        // Remove supplemental rules installed by older app versions.
        File(parent, "thien_site_js.json").delete()
        ParserSourceBootstrap.init(context)
        SiteConfigStore.reload()
    }

    private fun writeJson(file: File, json: Any) {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Không tạo được thư mục dữ liệu parser." } }
        val text = when (json) {
            is JSONObject -> json.toString(2)
            is org.json.JSONArray -> json.toString(2)
            else -> error("Loại JSON không được hỗ trợ.")
        }
        file.writeText(text, Charsets.UTF_8)
    }

    private fun isSafeSegment(value: String): Boolean =
        value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9._-]{1,180}"))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
