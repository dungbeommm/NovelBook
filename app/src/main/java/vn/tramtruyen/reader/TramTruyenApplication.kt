package vn.tramtruyen.reader

import vn.tramtruyen.reader.engine.network.AppDns

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.google.android.play.core.splitcompat.SplitCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import vn.tramtruyen.reader.engine.Config
import vn.tramtruyen.reader.engine.HttpClient
import vn.tramtruyen.reader.engine.network.ProxyManager
import vn.tramtruyen.reader.engine.sources.SiteConfigStore
import vn.tramtruyen.reader.engine.sources.AdBlockManager
import vn.tramtruyen.reader.engine.sources.BrowserHistoryManager
import vn.tramtruyen.reader.engine.speech.TtsManager
import vn.tramtruyen.reader.engine.catalog.ParserSourceBootstrap
import vn.tramtruyen.reader.engine.catalog.DownloadOptions
import java.io.File
import android.util.Base64
import android.security.keystore.KeyProperties
import android.security.keystore.KeyGenParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * App init: ReadingEngine on background thread + shared Coil ImageLoader for fast covers.
 */
class TramTruyenApplication : Application(), ImageLoaderFactory {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SplitCompat.install(this)
    }

    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        AppDns.init(applicationContext)
        AdBlockManager.init(applicationContext)
        DownloadOptions.init(applicationContext)
        BrowserHistoryManager.init(applicationContext)
        thread(isDaemon = true, name = "reader-core-init") { initCore() }
    }

    override fun newImageLoader(): ImageLoader {
        // Header/cookie work stays on OkHttp's I/O thread. Doing this from
        // CoverArt composition used to steal time from fast list flings.
        val sourceHeaders = Interceptor { chain -> proceedCoverRequest(chain) }
        val ok = try {
            HttpClient.sharedClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .addNetworkInterceptor(sourceHeaders)
                .build()
        } catch (_: Exception) {
            AppDns.clientBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .addNetworkInterceptor(sourceHeaders)
                .build()
        }
        val isLowRamDevice = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
        val memoryCachePercent = if (isLowRamDevice) 0.08 else 0.16
        return ImageLoader.Builder(this)
            .okHttpClient(ok)
            .crossfade(120)
            // TOI UU: anh bia khong can alpha -> RGB_565 giam mot nua bo nho va
            // thoi gian giai ma, cuon danh sach truyen do giat hon han.
            .allowRgb565(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Giới hạn thích ứng giúp máy RAM thấp và multi-window tránh
                    // GC/trim liên tục; cache đĩa vẫn giữ ảnh để tải lại nhanh.
                    .maxSizePercent(memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_covers"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    private fun proceedCoverRequest(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        val isWikiCv = host == "wikicv.org" || host.endsWith(".wikicv.org") ||
            host == "wikicv.net" || host.endsWith(".wikicv.net")
        val isTruyenNet = host == "truyennet.org" || host.endsWith(".truyennet.org")
        if (!isWikiCv && !isTruyenNet) return chain.proceed(request)

        val cookie = runCatching {
            CookieManager.getInstance().getCookie(request.url.toString())
        }.getOrNull().orEmpty()
        val builder = request.newBuilder()
            .header("User-Agent", browserUserAgent)
            .header("Referer", if (isWikiCv) "https://wikicv.org/" else "https://truyennet.org/")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        return chain.proceed(builder.build())
    }

    private fun initCore() {
        try {
            val dataDir = File(filesDir, "data").apply { mkdirs() }
            Config.init(dataDir)
            Config.appContext = applicationContext

            // Prefer the single downloaded WebParser bundle, with APK assets as fallback.
            try { ParserSourceBootstrap.init(applicationContext) }
            catch (e: Exception) { Log.e(TAG, "parser source init: ${e.message}") }
            try { SiteConfigStore.ensureLoaded() } catch (e: Exception) { Log.e(TAG, "parser.json: ${e.message}") }

            try { ProxyManager.load() } catch (e: Exception) { Log.e(TAG, "proxy.json: ${e.message}") }
            loadTtsKeys()
            try { TtsManager.init(applicationContext) } catch (e: Exception) { Log.e(TAG, "TTS init: ${e.message}") }

        } catch (e: Exception) {
            Log.e(TAG, "Init core loi: ${e.message}", e)
        } finally {
            readyLatch.countDown()
        }
    }

    private fun loadTtsKeys() {
        try {
            Config.geminiApiKey = geminiApiKey(this)
            Config.zaloApiKey = zaloApiKey(this)
        } catch (e: Exception) {
            Log.e(TAG, "Load TTS keys loi: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReadingEngine"
        @Volatile var appContext: Context? = null
            private set
        private val readyLatch = CountDownLatch(1)

        fun awaitReady() {
            try { readyLatch.await() } catch (_: InterruptedException) {}
        }

        private const val TTS_PREFS = "tts_keys"
        private const val LEGACY_KEY_GEMINI = "gemini_api_key"
        private const val KEY_GEMINI_ENCRYPTED = "gemini_api_key_v2"
        private const val KEY_ZALO_ENCRYPTED = "zalo_api_key_v2"
        private const val KEYSTORE_ALIAS = "novelreader_gemini_key"

        private fun secretKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
        }

        private fun encryptSecret(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(payload, Base64.NO_WRAP)
        }

        private fun decryptSecret(value: String): String {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            require(payload.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
        }

        /** Read encrypted Gemini key and migrate the old plaintext value once. */
        fun geminiApiKey(context: Context): String = try {
            val prefs = context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_GEMINI_ENCRYPTED, null)?.let(::decryptSecret) ?: run {
                val legacy = prefs.getString(LEGACY_KEY_GEMINI, "").orEmpty()
                if (legacy.isNotBlank()) {
                    prefs.edit()
                        .putString(KEY_GEMINI_ENCRYPTED, encryptSecret(legacy))
                        .remove(LEGACY_KEY_GEMINI)
                        .apply()
                }
                legacy
            }
        } catch (e: Exception) {
            Log.e(TAG, "Doc Gemini key loi: ${e.message}")
            ""
        }

        /**
         * Luu khoa Gemini va AP DUNG NGAY, khong doi khoi dong lai app:
         * ghi SharedPreferences (de lan sau TramTruyenApplication.loadTtsKeys doc lai),
         * cap nhat Config, roi dung lai bang provider cloud cua TtsManager.
         */

        fun zaloApiKey(context: Context): String = try {
            val encrypted = context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ZALO_ENCRYPTED, null)
            if (encrypted.isNullOrBlank()) "" else decryptSecret(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Đọc Zalo key lỗi: ${e.message}")
            ""
        }

        fun saveZaloApiKey(context: Context, key: String) {
            val clean = key.trim()
            try {
                val edit = context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE).edit()
                if (clean.isBlank()) edit.remove(KEY_ZALO_ENCRYPTED)
                else edit.putString(KEY_ZALO_ENCRYPTED, encryptSecret(clean))
                edit.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Lưu Zalo key lỗi: ${e.message}")
            }
            Config.zaloApiKey = clean
            try { TtsManager.reloadCloudProviders() } catch (e: Exception) {
                Log.e(TAG, "Reload Zalo TTS lỗi: ${e.message}")
            }
        }

        fun saveGeminiApiKey(context: Context, key: String) {
            val clean = key.trim()
            try {
                context.getSharedPreferences(TTS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_GEMINI_ENCRYPTED, encryptSecret(clean))
                    .remove(LEGACY_KEY_GEMINI)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Luu gemini key loi: ${e.message}")
            }
            Config.geminiApiKey = clean
            try { TtsManager.reloadCloudProviders() } catch (e: Exception) {
                Log.e(TAG, "Reload TTS loi: ${e.message}")
            }
        }
    }
}
