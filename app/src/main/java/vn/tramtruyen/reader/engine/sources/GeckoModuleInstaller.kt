package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.network.AppDns

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.play.core.splitcompat.SplitCompat
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

object GeckoModuleInstaller {
    private const val GECKO_BUCKET =
        "https://huggingface.co/buckets/uri410/NovelBook_Cloud/resolve/runtime/gecko"
    private const val MODULE_NAME = "gecko"
    private const val MODULE_APK = "split_gecko.apk"
    private const val ARM64_CONFIG_APK = "split_gecko.config.arm64_v8a.apk"
    private const val STATUS_ACTION = "vn.tramtruyen.reader.GECKO_MODULE_INSTALL_STATUS"
    private const val INSTALL_TIMEOUT_MS = 300_000L

    private val installLock = Mutex()
    private val client = AppDns.clientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Suppress("DEPRECATION")
    fun isPackageInstalled(context: Context): Boolean {
        val splits: Array<out String>? = context.packageManager.getPackageInfo(context.packageName, 0).splitNames
        return splits?.contains(MODULE_NAME) == true
    }

    fun canInstall(activity: Activity): Boolean = activity.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null)),
        )
    }

    suspend fun install(
        activity: Activity,
        onProgress: (Int) -> Unit = {},
        onInstalling: () -> Unit = {},
    ) = withContext(Dispatchers.Main.immediate) {
        installLock.withLock {
            if (!isPackageInstalled(activity)) {
                check(canInstall(activity)) { "Cho phép NovelBook cài ứng dụng từ nguồn ngoài rồi thử lại." }
                val url = moduleDownloadUrl(activity)
                val downloaded = File.createTempFile("gecko-", ".apk", activity.cacheDir)
                try {
                    download(url, downloaded, onProgress)
                    onInstalling()
                    installSplit(activity, downloaded)
                } finally {
                    downloaded.delete()
                }
            }
            runCatching {
                SplitCompat.install(activity.applicationContext)
                SplitCompat.installActivity(activity)
            }
            Unit
        }
    }

    private fun moduleDownloadUrl(context: Context): String {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        check(!version.isNullOrBlank()) { "Không xác định được phiên bản NovelBook đang cài." }
        return GECKO_BUCKET.toHttpUrl().newBuilder()
            .addPathSegment("v$version")
            .addPathSegment("TramTruyen-Gecko.apk")
            .build()
            .toString()
    }

    private suspend fun download(url: String, apk: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                check(response.code != 404) { "Hugging Face chưa có module Gecko cho bản NovelBook đang cài." }
                check(response.isSuccessful) { "Không tải được Gecko (HTTP ${response.code})." }
                val body = response.body
                val total = body.contentLength()
                var downloaded = 0L
                var lastProgress = -2
                body.byteStream().use { input ->
                    apk.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val progress = if (total > 0) (downloaded * 100 / total).toInt().coerceAtMost(100) else -1
                            if (lastProgress != progress) {
                                withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                                lastProgress = progress
                            }
                        }
                    }
                }
                check(downloaded > 0 && (total < 0 || downloaded == total)) { "Tệp Gecko tải chưa đủ." }
            }
            ZipFile(apk).use { zip ->
                if (zip.getEntry(MODULE_APK) != null) {
                    check("arm64-v8a" in Build.SUPPORTED_ABIS && zip.getEntry(ARM64_CONFIG_APK) != null) {
                        "Gói Gecko thiếu split ARM64 tương thích."
                    }
                    return@withContext
                }
                check(zip.getEntry("AndroidManifest.xml") != null &&
                    zip.getEntry("assets/stv_reader/manifest.json") != null
                ) { "Tệp tải về không phải module Gecko của NovelBook." }
                check(Build.SUPPORTED_ABIS.any { zip.getEntry("lib/$it/libxul.so") != null }) {
                    "Gói Gecko thiếu thư viện phù hợp với kiến trúc thiết bị."
                }
            }
        }

    private suspend fun installSplit(activity: Activity, apk: File) {
        val packageInstaller = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING)
            .apply {
                setAppPackageName(activity.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val sessionId = packageInstaller.createSession(params)
        val action = "$STATUS_ACTION.$sessionId"
        var completed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                withContext(Dispatchers.IO) {
                    ZipFile(apk).use { zip ->
                        if (zip.getEntry(MODULE_APK) == null) {
                            session.openWrite(MODULE_APK, 0, apk.length()).use { output ->
                                apk.inputStream().use { it.copyTo(output) }
                                session.fsync(output)
                            }
                        } else {
                            for (name in listOf(MODULE_APK, ARM64_CONFIG_APK)) {
                                val entry = checkNotNull(zip.getEntry(name)) { "Gói Gecko thiếu $name." }
                                check(!entry.isDirectory && entry.size > 0) { "Split Gecko không hợp lệ: $name." }
                                session.openWrite(name, 0, entry.size).use { output ->
                                    zip.getInputStream(entry).use { it.copyTo(output) }
                                    session.fsync(output)
                                }
                            }
                        }
                    }
                    apk.delete()
                }
                val statusIntent = Intent(action).setPackage(activity.packageName)
                val callback = PendingIntent.getBroadcast(
                    activity, sessionId, statusIntent, PendingIntent.FLAG_MUTABLE,
                )
                val status = try {
                    withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                        awaitInstallStatus(activity, action) { session.commit(callback.intentSender) }
                    }
                } finally {
                    callback.cancel()
                }
                checkNotNull(status) { "Hết thời gian chờ Android xác nhận cài Gecko." }
                val code = status.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val detail = status.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                check(code == PackageInstaller.STATUS_SUCCESS) {
                    when (code) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Đã hủy cài Gecko."
                        PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_CONFLICT ->
                            "Gói Gecko không khớp với bản app đang cài."
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Gói Gecko không tương thích với thiết bị."
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android đã chặn cài Gecko. Kiểm tra quyền cài từ nguồn ngoài."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "Thiết bị không đủ dung lượng để cài Gecko."
                        else -> "Android báo lỗi cài Gecko ($code)."
                    } + if (detail.isNullOrBlank()) "" else " $detail"
                }
                completed = true
            }
        } finally {
            if (!completed) runCatching { packageInstaller.abandonSession(sessionId) }
        }
    }

    private suspend fun awaitInstallStatus(activity: Activity, action: String, commit: () -> Unit): Intent {
        val context = activity.applicationContext
        val receiver = object : BroadcastReceiver() {
            var onStatus: (Intent) -> Unit = {}

            override fun onReceive(context: Context, intent: Intent) = onStatus(intent)
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                receiver.onStatus = { intent ->
                    if (continuation.isActive) {
                        val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            runCatching {
                                val confirm = checkNotNull(
                                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
                                ) { "Android không mở được màn hình xác nhận cài Gecko." }
                                activity.startActivity(confirm)
                            }.onFailure { continuation.resumeWithException(it) }
                        } else {
                            continuation.resume(intent)
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                commit()
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
