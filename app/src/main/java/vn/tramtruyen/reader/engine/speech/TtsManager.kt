package vn.tramtruyen.reader.engine.speech

import android.content.Context
import android.util.Log
import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.Config
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Quan ly toan bo provider TTS.
 * Voice key dang "provider:voice_id".
 */
object TtsManager {

    private const val TAG = "TtsManager"

    private lateinit var android: AndroidTtsProvider
    private lateinit var edge: EdgeTtsProvider
    private lateinit var capcut: CapcutTtsProvider
    private lateinit var gtts: GttsProvider
    private lateinit var tiktok: TikTokTtsProvider
    private lateinit var vbook: VBookTtsProvider
    private val cloud = LinkedHashMap<String, TtsProvider>()
    /** Nguon giong bi tat trong Cai dat (key provider, chu thuong). */
    @Volatile var disabledProviders: Set<String> = emptySet()
        private set

    fun setDisabledProviders(keys: Set<String>) {
        disabledProviders = keys.map { it.lowercase() }.filterNot { it == "offline" }.toSet()
    }

    fun isProviderDisabled(providerKey: String): Boolean =
        providerKey.lowercase() in disabledProviders
    @Volatile private var initialized = false
    /** Giữ để thử init lại khi lần init lúc mở app đã thất bại. */
    @Volatile private var appContext: Context? = null

    /** Thong diep loi gan nhat — UI doc de hien thi cu the. */
    @Volatile var lastError: String? = null
        private set

    const val DEFAULT_VOICE = "gtts:vi"

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            android = AndroidTtsProvider(context)
            edge = EdgeTtsProvider()
            capcut = CapcutTtsProvider(context.applicationContext)
            gtts = GttsProvider()
            tiktok = TikTokTtsProvider()
            vbook = VBookTtsProvider()
            VBookTtsRuntime.initialize(context.applicationContext)
            reloadCloudProviders()
            initialized = true
        }
    }

    /**
     * Init lúc mở app mà lỗi thì trước đây TTS chết cả phiên (TramTruyenApplication
     * chỉ ghi log). Nay lần synthesize đầu tiên thử init lại một lần; init()
     * tự đồng bộ bằng synchronized nên nhiều lời gọi đồng thời vẫn an toàn.
     */
    private fun ensureInitialized() {
        if (initialized) return
        val ctx = appContext ?: return
        try {
            init(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init retry: ${e.message}")
        }
    }

    fun reloadCloudProviders() {
        synchronized(this) {
            cloud.clear()
            GeminiTtsProvider(Config.geminiApiKey).let { if (it.isConfigured()) cloud["gemini"] = it }
            ZaloTtsProvider(Config.zaloApiKey).let { if (it.isConfigured()) cloud["zalo"] = it }
        }
    }

    fun clearLastError() { lastError = null }

    private fun noteError(msg: String) {
        lastError = msg
        Log.w(TAG, msg)
    }

    private fun providerFor(key: String): Pair<TtsProvider?, String> {
        val idx = key.indexOf(':')
        if (idx < 0) return null to key
        val provider = key.substring(0, idx).lowercase()
        val voiceId = key.substring(idx + 1)
        return when (provider) {
            "android" -> android to voiceId
            "edge" -> edge to voiceId
            "capcut" -> capcut to voiceId
            "gtts" -> gtts to voiceId
            "tiktok" -> tiktok to voiceId
            "vbook" -> vbook to voiceId
            // Cloud providers nhan FULL key (gemini:Kore / zalo:1) de map dung bang giong.
            in cloud.keys -> cloud[provider] to key
            else -> null to voiceId
        }
    }

    suspend fun synthesize(text: String, voiceKey: String?, speed: Double = 1.0, pitch: Double = 1.0): ByteArray? {
        clearLastError()
        ensureInitialized()
        if (!initialized) {
            noteError("TTS chưa khởi tạo được — hãy thử lại sau")
            return null
        }
        val key = TtsVoicePolicy.migrate(voiceKey)
        val requestedProvider = key.substringBefore(':').lowercase()
        if (isProviderDisabled(requestedProvider)) {
            noteError("Nguồn giọng ${requestedProvider} đang tắt trong Cài đặt → Âm thanh & giọng đọc")
            return null
        }
        val (provider, voiceId) = providerFor(key)
        if (provider == null) {
            noteError("Không nhận diện được giọng: $key")
            // Invalid voice keys still prefer online speech; do not silently
            // switch the user's requested Google-online default to device TTS.
            if (!isProviderDisabled("gtts")) gtts.synthesize(text, "vi", speed, pitch)?.let { return it }
            if (!isProviderDisabled("edge")) return edge.synthesize(text, "vi-VN-HoaiMyNeural", speed, pitch)
            return null
        }
        return try {
            val audio = provider.synthesize(text, voiceId, speed, pitch)
            if (audio == null || audio.isEmpty()) {
                val detail = when (provider) {
                    is GeminiTtsProvider -> provider.lastError
                    is EdgeTtsProvider -> provider.lastError
                    is TikTokTtsProvider -> provider.lastError
                    else -> null
                }
                noteError(detail ?: "Giọng ${key.substringBefore(':')} không tạo được âm thanh")
                null
            } else {
                audio
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ApiError) {
            noteError(e.message ?: "Không tạo được âm thanh")
            throw e
        } catch (e: Throwable) {
            noteError(e.message?.takeIf { it.isNotBlank() } ?: "Lỗi TTS: ${e.javaClass.simpleName}")
            null
        }
    }

    fun getAllVoices(): List<JsonObject> {
        val seen = HashSet<String>()
        val out = ArrayList<JsonObject>()
        fun add(list: List<JsonObject>) {
            for (v in list) {
                val id = v["voice_id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (isProviderDisabled(id.substringBefore(':'))) continue
                if (seen.add(id)) out.add(v)
            }
        }
        add(android.voiceList)
        add(edge.voiceList)
        add(capcut.voiceList)
        add(gtts.voiceList)
        add(tiktok.voiceList)
        add(vbook.voiceList)
        for (p in cloud.values) add(p.voiceList)
        return out
    }

    fun statusMap(): Map<String, Any> = mapOf(
        "android_available" to true,
        "edge_available" to true,
        "capcut_available" to true,
        "gtts_available" to true,
        "tiktok_available" to tiktok.isConfigured(),
        "vbook_available" to vbook.isConfigured(),
        "cloud_providers" to cloud.keys.toList(),
        "total_voices" to getAllVoices().size,
        "default_voice" to DEFAULT_VOICE,
        "last_error" to (lastError ?: ""),
    )

    fun providerInfo(): List<ProviderInfo> {
        val list = ArrayList<ProviderInfo>()
        list.add(ProviderInfo("android", "Android TTS", true, true, android.voiceList.size))
        list.add(ProviderInfo("edge", "Edge TTS", true, true, edge.voiceList.size))
        list.add(ProviderInfo("capcut", "CapCut TTS (vi-VN)", true, true, capcut.voiceList.size))
        list.add(ProviderInfo("gtts", "Google Translate TTS", true, true, gtts.voiceList.size))
        list.add(ProviderInfo("tiktok", "TikTok TTS (trực tuyến)", tiktok.isConfigured(), true, tiktok.voiceList.size))
        list.add(ProviderInfo("vbook", "VBook AI (ngoại tuyến)", vbook.isConfigured(), true, vbook.voiceList.size))
        for ((key, p) in cloud) list.add(ProviderInfo(key, key, p.isConfigured(), false, p.voiceList.size))
        return list
    }

    data class ProviderInfo(val key: String, val name: String, val configured: Boolean, val free: Boolean, val voices: Int)

    fun cloudProviderKeys(): List<String> = cloud.keys.toList()
}
