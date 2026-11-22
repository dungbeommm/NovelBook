package vn.tramtruyen.reader.engine.speech

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Online TikTok voices via Weilbyte's third-party proxy; selected explicitly, never a fallback. */
class TikTokTtsProvider : TtsProvider {
    private val mutex = Mutex()
    private var nextAllowedNanos = 0L
    private val http = TtsUtil.http.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    @Volatile var lastError: String? = null
        private set

    override val voiceList: List<JsonObject> = TikTokTtsProtocol.voices.map { v ->
        TtsUtil.voice("tiktok:${v.id}", v.name, v.gender, "Trực tuyến · proxy bên thứ ba", v.language, "TikTok TTS", "tiktok", false)
    }
    // Means no API key is required; not a claim of service uptime.
    override fun isConfigured(): Boolean = true

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? = mutex.withLock {
        lastError = null
        try {
            val body = TikTokTtsProtocol.requestBody(text, voiceId)
            val waitMs = ((nextAllowedNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            val request = Request.Builder().url(TikTokTtsProtocol.ENDPOINT)
                .header("User-Agent", "TramTruyen/1.000 TikTokTTS")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try { fetchAudio(request) }
            finally { nextAllowedNanos = System.nanoTime() + 500_000_000L }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastError = if (e is IOException) "Không kết nối được TikTok TTS hoặc đã hết thời gian chờ; kiểm tra mạng rồi thử lại"
                else e.message ?: "TikTok TTS không tạo được âm thanh"
            null
        }
    }

    private suspend fun fetchAudio(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val audio = response.use { res ->
                        check(res.isSuccessful) { TikTokTtsProtocol.httpError(res.code) }
                        val responseBody = res.body ?: error("TikTok TTS: phản hồi rỗng")
                        check(responseBody.contentLength() <= TikTokTtsProtocol.MAX_RESPONSE_BYTES) { "TikTok TTS: phản hồi quá lớn" }
                        val output = ByteArrayOutputStream()
                        responseBody.byteStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                check(output.size() + n <= TikTokTtsProtocol.MAX_RESPONSE_BYTES) { "TikTok TTS: phản hồi quá lớn" }
                                output.write(buffer, 0, n)
                            }
                        }
                        TikTokTtsProtocol.decodeResponse(output.toString(Charsets.UTF_8.name()))
                    }
                    if (continuation.isActive) continuation.resume(audio)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
