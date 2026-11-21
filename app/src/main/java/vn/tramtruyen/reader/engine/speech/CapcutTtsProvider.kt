package vn.tramtruyen.reader.engine.speech

import android.content.Context
import android.os.SystemClock
import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.CapcutVietnameseNormalizer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * CapCut TTS qua common-task API không chính thức.
 *
 * Điểm an toàn/ổn định quan trọng:
 * - Một device profile ổn định cho mỗi bản cài; device_id/iid/tdid luôn giống nhau.
 * - Tối đa MAX_CONCURRENT_TASKS task chạy song song (semaphore); nhịp submit
 *   vẫn được giữ toàn cục qua throttleMutex để tránh bị CapCut chặn.
 * - x-ss-stub là MD5 chữ thường đúng với client tham chiếu.
 * - Chấp nhận cả trạng thái "succeed" lẫn "success".
 * - Không nuốt lỗi ret/errmsg; TtsManager đưa thông báo thật lên giao diện.
 * - File tải về phải qua kiểm tra magic bytes, tránh phát trang lỗi JSON/HTML.
 *
 * Mọi chuỗi giao thức (SSML, query, chuỗi ký...) nằm ở [CapcutTtsProtocol] để
 * kiểm thử độc lập; lớp này chỉ giữ phần phụ thuộc Android/mạng.
 */
class CapcutTtsProvider(context: Context) : TtsProvider {

    companion object {
        private const val BASE_URL = "https://editor-api-sg.capcutapi.com"
        private const val PREFS_NAME = "capcut_tts_device_v2"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_MODEL = "device_model"
        private const val DEFAULT_VOICE = "BV421_vivn_streaming"
        private const val MAX_TEXT_LENGTH = 1_200
        private const val MIN_SUBMIT_INTERVAL_MS = 600L
        private const val POLL_TIMES = 60
        /** Số lần poll liên tiếp mà phản hồi query thiếu mảng tasks trước khi bỏ cuộc. */
        private const val MAX_MALFORMED_POLLS = 3
        private const val POLL_FAST_TIMES = 6
        private const val POLL_FAST_DELAY_MS = 300L
        private const val POLL_DELAY_MS = 700L

        /** Số task CapCut chạy song song tối đa; throttle submit vẫn áp dụng toàn cục. */
        private const val MAX_CONCURRENT_TASKS = 3
        private val DEVICE_MODELS = listOf("MacBookPro17,4", "MacBookPro18,1", "MacBookPro18,3")
        private val RETRY_DELAYS_MS = longArrayOf(1_200L, 2_800L)
    }

    private val jsonMedia = "application/json".toMediaType()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    // Nhiều câu được tổng hợp song song (tối đa MAX_CONCURRENT_TASKS) thay vì
    // tuần tự tuyệt đối như trước — điểm then chốt để xóa delay giữa các câu.
    private val taskSemaphore = Semaphore(MAX_CONCURRENT_TASKS)
    // Chỉ giữ nhịp submit toàn cục (chống bị chặn), không tuần tự hóa cả task.
    private val throttleMutex = Mutex()
    private val deviceProfile: DeviceProfile = loadOrCreateDeviceProfile()
    private var lastSubmitElapsed = 0L

    private val pubKeyPem = (
        "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmTd34Lw4b7IuldSXh/zY\n" +
            "CMla+ITdGG5TeWz6ad+OySd4r+IrY45AoqrYUxhQ2dl+7z+i7r/5vEa8rr39BYfB\n" +
            "8AGMQLmZA8HmgpWBsqrn/V6daUALkKnkLb70Fn32CJigIuGXAYqxUdGuI340aC+0\n" +
            "v5Es3puJsHyzf01/AelE4Cdc6bZhQrASJLBh8R3BQToYClmDVSDUQk28o8sl/guA\n" +
            "Z4n303Vj+6Siv1HayPCdV6kpVVnMBAG4+umUbwGmn132N3fgpzLarFF3XyWmS1zh\n" +
            "D/J07iM/rP8GDO9IskHNHd2phrO0G6KzrcFAnTBHjVv+hCBEfzN/no3FNA9AuC36\n" +
            "mwIDAQAB\n" +
            "-----END PUBLIC KEY-----"
        )

    private data class DeviceProfile(val id: String, val model: String)
    private data class VoiceInfo(val name: String, val resourceId: String, val gender: String)
    private class CapcutRequestException(
        message: String,
        val retryable: Boolean,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private val viVoices = linkedMapOf(
        "BV421_vivn_streaming" to VoiceInfo("Nhỏ Ngọt Ngào", "7252594014782755330", "FEMALE"),
        "vi_female_huong" to VoiceInfo("Giọng Nữ Phổ Thông", "7264854897953083905", "FEMALE"),
        "BV074_streaming_dsp" to VoiceInfo("Giọng Bé", "7550087831092251920", "FEMALE"),
        "BV074_streaming" to VoiceInfo("Cô Gái Hoạt Ngôn", "7102355709945188865", "FEMALE"),
        "vi-VN-HoaiMyNeural" to VoiceInfo("Hoài My (CapCut)", "7371666434650280464", "FEMALE"),
        "vi-VN-NamMinhNeural" to VoiceInfo("Nam Minh (CapCut)", "7371666524727153168", "MALE"),
        "BV075_streaming_vibrato_dsp" to VoiceInfo("Việt Méo", "7569450639810465040", "MALE"),
        "BV562_streaming" to VoiceInfo("Mai", "7483736254694035984", "FEMALE"),
        "multi_female_yangguangnv_uranus_bigtts" to VoiceInfo("Ban Mai", "7637456432522218773", "FEMALE"),
        "multi_female_richgirl_uranus_bigtts" to VoiceInfo("Review Phim new", "7637460351541447956", "FEMALE"),
        "multi_female_quanweinv_uranus_bigtts" to VoiceInfo("Bản Tin 1", "7637458743197732117", "FEMALE"),
        "multi_female_stokie_uranus_bigtts" to VoiceInfo("Review Phim 4", "7637456729696996628", "FEMALE"),
        "multi_female_sisi_uranus_bigtts" to VoiceInfo("Bản Tin nữ", "7637455857285860629", "FEMALE"),
        "multi_female_daqi_uranus_bigtts" to VoiceInfo("Review Phim 3", "7637451983389019409", "FEMALE"),
        "multi_female_kiwi_uranus_bigtts" to VoiceInfo("Sunny Idol", "7637457995882089749", "FEMALE"),
        "BV075_streaming_demon_dsp" to VoiceInfo("Kenny Đại Đế", "7569442422665661712", "MALE"),
        "BV075_streaming_robot_dsp" to VoiceInfo("Robot VN", "7538698409633516816", "MALE"),
        // LỖI DỮ LIỆU ĐÃ BIẾT (chưa sửa — cần ID đúng từ CapCut): resource_id dưới
        // đây trùng với của "Review Phim 4" (multi_female_stokie_uranus_bigtts,
        // FEMALE) nên giọng nam này nhiều khả năng tổng hợp ra giọng nữ. Không
        // tự ý thay số; cần tra resource_id đúng của multi_male_felipe_uranus_bigtts.
        "multi_male_felipe_uranus_bigtts" to VoiceInfo("Giọng Nam Trầm", "7637456729696996628", "MALE"),
        "multi_female_peiqi_uranus_bigtts" to VoiceInfo("Giọng Gái Mới Lớn", "7637458789033151751", "FEMALE"),
        "multi_female_xinwenjieshuo_uranus_bigtts" to VoiceInfo("Nam bản tin", "7637455039719640327", "MALE"),
        "multi_female_tianmeijieshuo_uranus_bigtts" to VoiceInfo("Quên Tên Tự Test", "7637460417295469832", "FEMALE"),
        "BV075_streaming" to VoiceInfo("Thanh Niên Tự Tin", "7102355803792740865", "MALE"),
        "BV560_streaming" to VoiceInfo("Alex Đại Đế", "7483736167565758992", "MALE"),
    )

    override fun isConfigured(): Boolean = true

    override val voiceList: List<JsonObject>
        get() = viVoices.map { (type, info) ->
            TtsUtil.voice(
                "capcut:$type",
                info.name,
                info.gender,
                "Neural",
                "vi-VN",
                "CapCut TTS",
                "capcut",
                false,
            )
        }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Double,
        pitch: Double,
    ): ByteArray? {
        val requestedId = voiceId.removePrefix("capcut:")
        val resolvedId = requestedId.takeIf { it in viVoices } ?: DEFAULT_VOICE
        val info = viVoices.getValue(resolvedId)
        val clean = CapcutVietnameseNormalizer.prepareForSsml(text)
        if (clean.isEmpty()) {
            throw ApiError(422, "Đoạn CapCut trống sau khi làm sạch văn bản")
        }
        if (clean.length > MAX_TEXT_LENGTH) {
            throw ApiError(422, "Đoạn CapCut quá dài (${clean.length} ký tự). Hãy chia văn bản thành các câu ngắn hơn.")
        }
        val rate = String.format(Locale.US, "%.1f", TtsUtil.clampSpeed(speed))

        return withContext(Dispatchers.IO) {
            try {
                taskSemaphore.withPermit {
                    // Một task phải tạo đúng một file hoàn chỉnh. Nối nhiều MP3 bằng
                    // ByteArray làm MediaExtractor chỉ đọc stream đầu rồi ngắt tiếng;
                    // việc chia câu đã do tầng gọi (PlayerViewModel) thực hiện.
                    synthesizeWithRetry(clean, resolvedId, info.resourceId, rate)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw ApiError(502, error.message?.takeIf(String::isNotBlank)
                    ?: "CapCut TTS không tạo được âm thanh")
            }
        }
    }

    // ---------- Device profile ----------

    private fun loadOrCreateDeviceProfile(): DeviceProfile {
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val id = storedId?.takeIf(CapcutTtsProtocol::isValidDeviceId)
            ?: CapcutTtsProtocol.newDeviceId(secureRandom)
        val storedModel = prefs.getString(KEY_DEVICE_MODEL, null)
        val model = storedModel?.takeIf { it in DEVICE_MODELS }
            ?: DEVICE_MODELS[(id.hashCode() and Int.MAX_VALUE) % DEVICE_MODELS.size]
        if (id != storedId || model != storedModel) {
            prefs.edit().putString(KEY_DEVICE_ID, id).putString(KEY_DEVICE_MODEL, model).apply()
        }
        return DeviceProfile(id, model)
    }

    private fun baseDevice(): Map<String, String> = linkedMapOf(
        "aid" to "359289",
        "app_name" to "CapCut",
        "appvr" to "8.7.0",
        "version_name" to "8.7.0",
        "version_code" to "8.7.0",
        "channel" to "capcutpc_google",
        "device_platform" to "mac",
        "device_type" to deviceProfile.model,
        "device_brand" to deviceProfile.model,
        "os_version" to "15.7.4",
        "device_id" to deviceProfile.id,
        "iid" to deviceProfile.id,
        "region" to "VN",
        "loc" to "VN",
        "lan" to "vi-VN",
        "pf" to "3",
        "tdid" to deviceProfile.id,
    )

    // ---------- Luồng synthesize với retry/throttle ----------

    private suspend fun synthesizeWithRetry(
        text: String,
        voice: String,
        resourceId: String,
        rate: String,
    ): ByteArray {
        var lastFailure: CapcutRequestException? = null
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
            try {
                awaitSubmitWindow()
                return synthesizeSync(text, voice, resourceId, rate)
            } catch (error: CapcutRequestException) {
                lastFailure = error
                if (!error.retryable) throw error
            }
        }
        throw lastFailure ?: CapcutRequestException("CapCut TTS không phản hồi", retryable = false)
    }

    private suspend fun awaitSubmitWindow() {
        // Giữ nhịp submit toàn cục ngay cả khi nhiều task song song: delay nằm
        // TRONG mutex nên hai coroutine không bao giờ submit sát nhau.
        throttleMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val remaining = MIN_SUBMIT_INTERVAL_MS - (now - lastSubmitElapsed)
            if (lastSubmitElapsed > 0L && remaining > 0L) delay(remaining)
            lastSubmitElapsed = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun synthesizeSync(
        text: String,
        voice: String,
        resourceId: String,
        rate: String,
    ): ByteArray {
        val device = baseDevice()
        val (babi, body) = ttsNewBody(text, voice, resourceId, rate, device)
        val newUrl = "$BASE_URL/lv/v1/common_task/new?${
            CapcutTtsProtocol.buildCommonQuery(device, babi, true)
        }"
        val headers = baseHeaders(device, body)
        headers["sign"] = signHeader(newUrl, device, headers.getValue("device-time"))
        val newJson = postJson(newUrl, headers, body, "Tạo task CapCut")
        val firstTask = tasks(newJson).firstOrNull()
            ?: throw CapcutRequestException("CapCut không trả task TTS", false)
        if (CapcutTtsProtocol.classifyStatus(primitive(firstTask["status"])) == CapcutTaskState.SUCCEEDED) {
            val urls = CapcutTtsProtocol.audioUrls(firstTask)
            if (urls.isNotEmpty()) return downloadFirstAudio(urls)
        }
        val taskId = taskId(firstTask)
            ?: throw CapcutRequestException("Task CapCut thiếu id", false)
        val token = primitive(firstTask["token"])?.takeIf { it.isNotBlank() }
            ?: throw CapcutRequestException("Task CapCut thiếu token", false)

        var malformedPolls = 0
        var pollFailures = 0
        var completedWithoutAudio = 0
        repeat(POLL_TIMES) { poll ->
            delay(if (poll < POLL_FAST_TIMES) POLL_FAST_DELAY_MS else POLL_DELAY_MS)
            val queryBody = queryBody(taskId, token)
            val queryUrl = "$BASE_URL/lv/v1/common_task/query?${
                CapcutTtsProtocol.buildCommonQuery(device, null, false)
            }"
            val queryHeaders = baseHeaders(device, queryBody)
            queryHeaders["sign"] = signHeader(queryUrl, device, queryHeaders.getValue("device-time"))
            val queryJson = try {
                postJson(queryUrl, queryHeaders, queryBody, "Hỏi trạng thái CapCut").also { pollFailures = 0 }
            } catch (error: CapcutRequestException) {
                if (!error.retryable || ++pollFailures >= MAX_MALFORMED_POLLS) {
                    throw CapcutRequestException(error.message.orEmpty(), false, error)
                }
                delay(RETRY_DELAYS_MS[pollFailures - 1])
                return@repeat
            }
            val returned = tasks(queryJson)
            val queryTask = returned.firstOrNull { taskId(it) == taskId }
                ?: returned.singleOrNull()?.takeIf { taskId(it) == null }
            if (queryTask == null) {
                // ret=0 nhưng không có mảng tasks: phản hồi dị dạng. Vài lần liên
                // tiếp như vậy nghĩa là máy chủ đã đổi dạng phản hồi — bỏ cuộc sớm
                // với lý do rõ ràng thay vì đốt hết 60 lần poll rồi báo timeout.
                malformedPolls++
                if (malformedPolls >= MAX_MALFORMED_POLLS) {
                    throw CapcutRequestException(
                        "CapCut trả phản hồi trạng thái không có danh sách task ($malformedPolls lần liên tiếp)",
                        false,
                    )
                }
                return@repeat
            }
            malformedPolls = 0
            val status = primitive(queryTask["status"])
            when (CapcutTtsProtocol.classifyStatus(status)) {
                CapcutTaskState.WAITING -> completedWithoutAudio = 0
                CapcutTaskState.FAILED -> {
                    val detail = primitive(queryTask["errmsg"])
                        ?: primitive(queryTask["message"])
                        ?: primitive(queryTask["fail_reason"])
                        ?: "không rõ nguyên nhân"
                    throw CapcutRequestException("Task CapCut thất bại ($status): $detail", false)
                }
                CapcutTaskState.SUCCEEDED -> {
                    val audioUrls = CapcutTtsProtocol.audioUrls(queryTask)
                    if (audioUrls.isEmpty()) {
                        if (++completedWithoutAudio >= MAX_MALFORMED_POLLS) {
                            throw CapcutRequestException("Task CapCut đã xong nhưng không có URL âm thanh", false)
                        }
                        return@repeat
                    }
                    // Nhiều ứng viên không còn là lý do dừng: thử lần lượt theo
                    // thứ tự ưu tiên (file gộp trước), URL đầu tiên tải về và qua
                    // kiểm tra magic bytes sẽ thắng.
                    return try {
                        downloadFirstAudio(audioUrls)
                    } catch (error: CapcutRequestException) {
                        throw CapcutRequestException(error.message.orEmpty(), false, error)
                    }
                }
            }
        }
        throw CapcutRequestException("Task CapCut quá thời gian chờ", false)
    }

    // ---------- Dựng request ----------

    private fun baseHeaders(device: Map<String, String>, bodyText: String): MutableMap<String, String> {
        val now = (System.currentTimeMillis() / 1_000L).toString()
        return linkedMapOf(
            "content-type" to "application/json",
            "appvr" to device.getValue("appvr"),
            "ch" to device.getValue("channel"),
            "device-time" to now,
            "lan" to device.getValue("lan"),
            "loc" to device.getValue("loc"),
            "pf" to device.getValue("pf"),
            "sign-ver" to "1",
            "tdid" to device.getValue("tdid"),
            // CapCut client tham chiếu dùng lowercase hexdigest.
            "x-ss-stub" to md5Hex(bodyText),
            "x-ss-dp" to device.getValue("aid"),
            "x-khronos" to now,
            "x-tt-trace-id" to CapcutTtsProtocol.traceId(UUID.randomUUID().toString().replace("-", "")),
            "user-agent" to "Cronet/TTNetVersion:1d7cc3b1 2025-07-16 QuicVersion:52c2b40d 2025-04-03",
            "store-country-code" to device.getValue("loc").lowercase(),
            "store-country-code-src" to "did",
            "is-dispatch-us-ttp" to "0",
            "is-app-region-us-ttp" to "0",
            "app-sdk-version" to device.getValue("appvr"),
            "appid" to device.getValue("aid"),
        )
    }

    private fun signHeader(url: String, device: Map<String, String>, deviceTime: String): String =
        md5Hex(CapcutTtsProtocol.signHeaderInput(url, device.getValue("appvr"), deviceTime, device.getValue("tdid")))

    private fun ttsNewBody(
        text: String,
        voice: String,
        resourceId: String,
        rate: String,
        device: Map<String, String>,
    ): Pair<String, String> {
        val babi = buildJsonObject {
            put("feature_entrance", "editor")
            put("feature_entrance_detail", "editor-feature-text_to_speech")
            put("feature_key", "text_to_speech")
            put("scenario", "video_editor")
        }.toString()
        val ssml = CapcutTtsProtocol.buildSsml(text, voice, resourceId, rate)
        val extraInfo = buildJsonObject { putJsonObject("benefit_info") {} }.toString()
        val payload = buildJsonObject {
            put("audio_format", "mp3")
            put("babi_param", babi)
            put("credit_disable", false)
            put("extra_info", extraInfo)
            // Yêu cầu máy chủ ghép audio. Nếu CapCut vẫn trả nhiều URL rời, phía
            // dưới sẽ từ chối phát thay vì lấy URL đầu và cắt mất phần còn lại.
            put("need_merge_voice", true)
            put("need_subtitle_timestamp", false)
            put("scene", "text_to_speech")
            put("ssml", ssml)
            put(
                "sign",
                rsaEncrypt(
                    CapcutTtsProtocol.payloadSignInput(
                        md5Hex(ssml),
                        extraInfo,
                        device.getValue("device_id"),
                        device.getValue("aid"),
                    ),
                ),
            )
        }.toString()
        val body = buildJsonObject {
            put("bind_id", UUID.randomUUID().toString())
            put("can_queue", true)
            put("enter_from", "text_to_speech")
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("context", UUID.randomUUID().toString())
                    put("payload", payload)
                    put("req_key", "sami_text_to_speech")
                    put("task_version", "v3")
                })
            }
        }.toString()
        return babi to body
    }

    private fun queryBody(taskId: String, token: String): String = buildJsonObject {
        putJsonArray("tasks") {
            add(buildJsonObject {
                put("bind_id", "")
                put("id", taskId)
                put("req_key", "sami_text_to_speech")
                put("task_version", "v3")
                put("token", token)
            })
        }
    }.toString()

    // ---------- HTTP ----------

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        bodyText: String,
        label: String,
    ): JsonObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(bodyText.toByteArray(Charsets.UTF_8).toRequestBody(jsonMedia))
        headers.forEach { (key, value) ->
            if (!key.equals("content-type", ignoreCase = true)) requestBuilder.header(key, value)
        }
        val response = try {
            TtsUtil.http.newCall(requestBuilder.build()).execute()
        } catch (error: Exception) {
            throw CapcutRequestException("$label: lỗi mạng (${error.message ?: error.javaClass.simpleName})", true, error)
        }
        response.use { http ->
            val raw = http.body.string()
            if (!http.isSuccessful) {
                val retryable = http.code == 408 || http.code == 429 || http.code >= 500
                throw CapcutRequestException("$label: HTTP ${http.code}${raw.take(240).takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}", retryable)
            }
            val root = try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (error: Exception) {
                throw CapcutRequestException("$label trả dữ liệu không phải JSON: ${raw.take(240)}", true, error)
            }
            val ret = primitive(root["ret"]) ?: primitive(root["code"]) ?: primitive(root["status_code"])
            val errmsg = primitive(root["errmsg"]) ?: primitive(root["message"]) ?: primitive(root["status_msg"])
            CapcutTtsProtocol.apiError(ret, errmsg)?.let { message ->
                throw CapcutRequestException(message, retryable = (ret?.toIntOrNull() ?: 0) in 500..599 &&
                    !CapcutTtsProtocol.isBlocked(ret, errmsg))
            }
            return root
        }
    }

    /** Thử các URL ứng viên theo thứ tự; chỉ lỗi khi mọi ứng viên đều hỏng. */
    private fun downloadFirstAudio(urls: List<String>): ByteArray {
        var lastFailure: CapcutRequestException? = null
        for (url in urls) {
            try {
                return downloadAudio(url)
            } catch (error: CapcutRequestException) {
                lastFailure = error
            }
        }
        throw lastFailure
            ?: CapcutRequestException("CapCut không có URL âm thanh khả dụng", false)
    }

    private fun downloadAudio(url: String): ByteArray {
        val response = try {
            TtsUtil.http.newCall(Request.Builder().url(url).get().build()).execute()
        } catch (error: Exception) {
            throw CapcutRequestException("Không tải được âm thanh CapCut: ${error.message}", true, error)
        }
        response.use { http ->
            if (!http.isSuccessful) {
                throw CapcutRequestException("Tải âm thanh CapCut lỗi HTTP ${http.code}", http.code >= 500)
            }
            val bytes = http.body.bytes()
            if (bytes.isEmpty()) {
                throw CapcutRequestException("CapCut trả tệp âm thanh rỗng", true)
            }
            if (!CapcutTtsProtocol.looksLikeSupportedAudio(bytes)) {
                throw CapcutRequestException(
                    "CapCut trả dữ liệu không phải âm thanh (${bytes.size} bytes, có thể là trang lỗi)",
                    true,
                )
            }
            return bytes
        }
    }

    // ---------- Tiện ích ----------

    private fun primitive(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun rsaEncrypt(message: String): String {
        val base64 = pubKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun tasks(response: JsonObject): List<JsonObject> {
        val data = CapcutTtsProtocol.objectPayload(response["data"])
        val items = (data?.get("tasks") ?: response["tasks"]) as? JsonArray
        return items.orEmpty().mapNotNull(CapcutTtsProtocol::objectPayload)
    }

    private fun taskId(task: JsonObject): String? =
        (primitive(task["id"]) ?: primitive(task["task_id"]))?.takeIf { it.isNotBlank() }
}
