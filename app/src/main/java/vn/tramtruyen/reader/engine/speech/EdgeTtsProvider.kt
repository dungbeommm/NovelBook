package vn.tramtruyen.reader.engine.speech

import vn.tramtruyen.reader.engine.network.AppDns

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Microsoft Edge Read Aloud TTS.
 *
 * Protocol values mirror the current edge-tts client. Microsoft rotates the
 * Chromium/GEC version and validates Origin, ConnectionId, MUID, and clock time;
 * stale values commonly fail the WebSocket upgrade with HTTP 403.
 */
class EdgeTtsProvider : TtsProvider {

    private val trustedToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    // Keep this in sync with the Chromium build used by the upstream edge-tts
    // client. Microsoft validates it together with Sec-MS-GEC during upgrade.
    private val chromiumFullVersion = "143.0.3650.75"
    private val chromiumMajorVersion = chromiumFullVersion.substringBefore('.')
    private val gecVersion = "1-$chromiumFullVersion"
    private val wssBase = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private val extensionOrigin = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private val outputFormat = "audio-24khz-48kbitrate-mono-mp3"

    /** Corrected from the server Date header after a rejected handshake. */
    private val clockSkewMs = AtomicLong(0L)
    @Volatile var lastError: String? = null
        private set

    private val voices = linkedMapOf(
        "vi-VN-HoaiMyNeural" to Triple("HoaiMy — Nữ (Edge Neural)", "FEMALE", "Neural"),
        "vi-VN-NamMinhNeural" to Triple("NamMinh — Nam (Edge Neural)", "MALE", "Neural"),
    )

    private val wsClient: OkHttpClient = AppDns.clientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override val voiceList: List<JsonObject>
        get() = voices.map { (id, info) ->
            TtsUtil.voice("edge:$id", info.first, info.second, info.third, "vi-VN", "Edge TTS", "edge", false)
        }

    override fun isConfigured(): Boolean = true

    private fun correctedNowMs(): Long = System.currentTimeMillis() + clockSkewMs.get()

    /** Sec-MS-GEC: corrected Windows file time, rounded down to five minutes. */
    private fun secMsGec(): String {
        var seconds = correctedNowMs() / 1000L + 11_644_473_600L
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        val source = "$ticks$trustedToken"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(correctedNowMs()))
    }

    private fun updateClockFromServer(response: Response?) {
        val serverDate = response?.header("Date") ?: return
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        )
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(serverDate)
            }.getOrNull() ?: continue
            clockSkewMs.set(parsed.time - System.currentTimeMillis())
            return
        }
    }

    /** Remove control characters rejected by the Read Aloud service. */
    private fun sanitize(text: String): String = buildString(text.length) {
        text.forEach { c ->
            val code = c.code
            append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c)
        }
    }.trim()

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class AttemptResult(
        val audio: ByteArray? = null,
        val statusCode: Int? = null,
        val response: Response? = null,
        val failure: String? = null,
    )

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Double,
        pitch: Double,
    ): ByteArray? {
        lastError = null
        val id = voiceId.removePrefix("edge:")
        if (!voices.containsKey(id)) {
            lastError = "Giọng Edge không hỗ trợ: $voiceId"
            return null
        }
        val clean = sanitize(text)
        if (clean.isEmpty()) {
            lastError = "Đoạn văn bản rỗng"
            return null
        }

        // Retry with a fresh ConnectionId/GEC/MUID. On 403, first correct device
        // clock from Microsoft's Date header before regenerating the five-minute GEC.
        var lastStatus: Int? = null
        var lastFailure: String? = null
        repeat(3) { attempt ->
            val result = synthesizeOnce(clean, id, speed, pitch)
            result.audio?.takeIf { it.isNotEmpty() }?.let { return it }
            lastStatus = result.statusCode
            lastFailure = result.failure
            updateClockFromServer(result.response)
            result.response?.close()
            val retryable = result.statusCode == null || result.statusCode == 403 ||
                result.statusCode == 408 || result.statusCode == 429 ||
                (result.statusCode ?: 0) >= 500
            if (!retryable) {
                lastError = "Edge TTS từ chối (HTTP ${result.statusCode})"
                return null
            }
            if (attempt < 2) delay(350L shl attempt)
        }
        lastError = when (lastStatus) {
            403 -> "Edge TTS bị chặn (403) — thử lại sau hoặc đổi giọng"
            429 -> "Edge TTS đang giới hạn tần suất — chờ rồi thử lại"
            null -> lastFailure?.let { "Edge TTS: $it" } ?: "Edge TTS không phản hồi / hết thời gian"
            else -> "Edge TTS lỗi HTTP $lastStatus"
        }
        return null
    }

    private suspend fun synthesizeOnce(
        clean: String,
        voiceId: String,
        speed: Double,
        pitch: Double,
    ): AttemptResult {
        val rate = TtsUtil.rateString(TtsUtil.clampSpeed(speed))
        val pitchString = TtsUtil.pitchHzString(TtsUtil.clampPitch(pitch))
        val requestId = connectionId()
        val connectId = connectionId()
        val muid = connectionId().uppercase(Locale.US)
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajorVersion.0.0.0 " +
            "Safari/537.36 Edg/$chromiumMajorVersion.0.0.0"
        val url = "$wssBase?TrustedClientToken=$trustedToken" +
            "&ConnectionId=$connectId" +
            "&Sec-MS-GEC=${secMsGec()}" +
            "&Sec-MS-GEC-Version=$gecVersion"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", extensionOrigin)
            .header("Sec-WebSocket-Version", "13")
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val result = CompletableDeferred<AttemptResult>()
        val audio = ByteArrayOutputStream()
        // Chỉ frame "Path:turn.end" mới chứng tỏ máy chủ đã gửi hết âm thanh.
        // Socket đóng trước frame này = âm thanh bị cắt cụt, phải thử lại.
        var turnEndSeen = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val config = "X-Timestamp:${timestamp()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                    "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"$outputFormat\"}}}}\r\n"
                if (!webSocket.send(config)) {
                    complete(null, null, null, "không gửi được cấu hình âm thanh")
                    return
                }

                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='$voiceId'><prosody pitch='$pitchString' rate='$rate' volume='+0%'>" +
                    escapeXml(clean) +
                    "</prosody></voice></speak>"
                val ssmlMessage = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${timestamp()}Z\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                if (!webSocket.send(ssmlMessage)) {
                    complete(null, null, null, "không gửi được văn bản")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val headerEnd = text.indexOf("\r\n\r\n")
                val headers = if (headerEnd >= 0) text.substring(0, headerEnd) else text
                if (headers.lineSequence().any { it.equals("Path:turn.end", ignoreCase = true) }) {
                    turnEndSeen = true
                    complete(audio.toByteArray(), null, null)
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val packet = bytes.toByteArray()
                if (packet.size < 2) return
                val headerLength = ((packet[0].toInt() and 0xFF) shl 8) or
                    (packet[1].toInt() and 0xFF)
                val audioStart = 2 + headerLength
                if (headerLength <= 0 || audioStart > packet.size) return
                val header = packet.copyOfRange(2, audioStart)
                    .toString(Charsets.US_ASCII)
                if (!header.lineSequence().any { it.equals("Path:audio", ignoreCase = true) }) return
                if (audioStart < packet.size) audio.write(packet, audioStart, packet.size - audioStart)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                complete(
                    null,
                    response?.code,
                    response,
                    t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName,
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // This is a WebSocket close code, not an HTTP status. Audio is
                // only valid if turn.end arrived; a close before that means the
                // stream was truncated, so report a (retryable) failure instead
                // of handing out a half sentence as success.
                complete(
                    audio.toByteArray().takeIf { turnEndSeen && it.isNotEmpty() },
                    null,
                    null,
                    if (turnEndSeen) reason.takeIf { it.isNotBlank() }
                    else "Kết nối đóng trước khi nhận đủ âm thanh (chưa có turn.end)",
                )
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                complete(
                    audio.toByteArray().takeIf { turnEndSeen && it.isNotEmpty() },
                    null,
                    null,
                    if (turnEndSeen) reason.takeIf { it.isNotBlank() }
                    else "Kết nối đóng trước khi nhận đủ âm thanh (chưa có turn.end)",
                )
            }

            private fun complete(
                bytes: ByteArray?,
                status: Int?,
                response: Response?,
                failure: String? = null,
            ) {
                if (!result.isCompleted) {
                    result.complete(AttemptResult(bytes, status, response, failure))
                }
            }
        }

        val webSocket = wsClient.newWebSocket(request, listener)
        val completed = withTimeoutOrNull(45_000L) { result.await() }
            ?: AttemptResult()
        webSocket.cancel()
        return completed
    }

    private fun connectionId(): String = UUID.randomUUID().toString().replace("-", "")
}
