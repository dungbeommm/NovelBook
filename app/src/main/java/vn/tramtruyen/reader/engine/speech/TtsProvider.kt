package vn.tramtruyen.reader.engine.speech

import vn.tramtruyen.reader.engine.network.AppDns

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Interface chung cho moi provider TTS. Tra ve bytes audio (mp3/wav) hoac null. */
interface TtsProvider {
    val voiceList: List<JsonObject>
    fun isConfigured(): Boolean
    suspend fun synthesize(text: String, voiceId: String, speed: Double = 1.0, pitch: Double = 1.0): ByteArray?
}

/** Tien ich dung chung cho TTS. */
object TtsUtil {
    val http: OkHttpClient = AppDns.clientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Tai su dung ket noi (keep-alive) khi chay nhieu yeu cau song song (vd CapCut)
        // -> giam do tre bat tay TLS, tang toc tao TTS.
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .build()

    fun clampSpeed(speed: Double): Double = maxOf(0.5, minOf(5.0, speed))
    fun clampPitch(pitch: Double): Double = maxOf(0.5, minOf(2.0, pitch))

    /** "{(speed-1)*100:+.0f}%" */
    fun rateString(speed: Double): String = signedInt((speed - 1.0) * 100) + "%"
    /** "{(pitch-1)*50:+.0f}Hz" */
    fun pitchHzString(pitch: Double): String = signedInt((pitch - 1.0) * 50) + "Hz"

    fun signedInt(v: Double): String {
        val r = Math.round(v).toInt()
        return if (r >= 0) "+$r" else "$r"
    }

    fun voice(
        voiceId: String, name: String, gender: String, tier: String,
        language: String, provider: String, icon: String, needsApiKey: Boolean,
    ): JsonObject = buildJsonObject {
        put("voice_id", voiceId)
        put("name", name)
        put("gender", gender)
        put("tier", tier)
        put("language", language)
        put("provider", provider)
        put("icon", icon)
        put("needs_api_key", needsApiKey)
    }
}
