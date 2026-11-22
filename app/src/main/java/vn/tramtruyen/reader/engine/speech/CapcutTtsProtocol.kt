package vn.tramtruyen.reader.engine.speech

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import java.util.Random

/** Các trạng thái task CapCut đã quan sát được ngoài thực tế. */
internal enum class CapcutTaskState { WAITING, SUCCEEDED, FAILED }

/**
 * Toàn bộ logic giao thức CapCut ở dạng thuần Kotlin (không Android/mạng) để
 * kiểm thử độc lập. Mọi chuỗi SSML/query/chuỗi ký đều được dựng tại đây;
 * provider chỉ lo phần Android (SharedPreferences, OkHttp, coroutine, RSA).
 */
internal object CapcutTtsProtocol {

    /** Device ID desktop hiện dùng chuỗi số 20 chữ số, bắt đầu bằng "76". */
    const val DEVICE_ID_LENGTH = 20

    private val SUCCESS_CODES = setOf("", "0", "200", "ok", "success", "succeed")

    /** Thứ tự khóa query giữ đúng client tham chiếu; không được xáo trộn. */
    private val COMMON_QUERY_KEYS = listOf(
        "app_name", "device_type", "os_version", "channel", "version_name",
        "device_brand", "device_id", "iid", "version_code", "device_platform", "aid",
    )

    fun classifyStatus(raw: String?): CapcutTaskState = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "succeed", "success", "succeeded", "done", "completed" -> CapcutTaskState.SUCCEEDED
        "fail", "failed", "error", "cancelled", "canceled" -> CapcutTaskState.FAILED
        else -> CapcutTaskState.WAITING
    }

    /** ret=-6 hoặc thông báo chứa "shark" = CapCut chặn client không chính thức. */
    fun isBlocked(ret: String?, errmsg: String?): Boolean =
        ret?.trim() == "-6" || errmsg?.contains("shark", ignoreCase = true) == true

    /** Thông báo lỗi hiển thị cho người dùng; null nếu [ret] là mã thành công. */
    fun apiError(ret: String?, errmsg: String?): String? {
        val code = ret?.trim().orEmpty()
        if (code in SUCCESS_CODES && !isBlocked(code, errmsg)) return null
        val detail = errmsg?.trim().takeUnless { it.isNullOrEmpty() } ?: "không có mô tả"
        return if (isBlocked(code, detail)) {
            "CapCut từ chối yêu cầu (ret=${code.ifBlank { "không có" }}: $detail). Có thể chọn giọng Edge hoặc Android."
        } else {
            "CapCut API lỗi $code: $detail"
        }
    }

    fun isValidDeviceId(value: String?): Boolean =
        value != null && value.length == DEVICE_ID_LENGTH && value.all(Char::isDigit)

    /** Tạo device ID mới: 20 chữ số, tiền tố "76" như client desktop. */
    fun newDeviceId(random: Random): String = buildString(DEVICE_ID_LENGTH) {
        append("76")
        repeat(DEVICE_ID_LENGTH - 2) { append(random.nextInt(10)) }
    }

    /** Phát hiện file chỉ chứa phần đầu của câu dài dù task báo thành công. */
    fun isSuspiciouslyShort(textLength: Int, durationMs: Long): Boolean {
        if (textLength < 60 || durationMs <= 0L) return false
        val estimatedMs = textLength * 1_000.0 / 14.0
        val safeFloorMs = maxOf(900.0, estimatedMs * 0.22).toLong()
        return durationMs < safeFloorMs
    }

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Dựng SSML đúng khuôn mẫu request desktop tham chiếu: root giữ lang en-US,
     * giọng Việt được chọn qua voice/resource_id, văn bản đã chuẩn hóa từ trước.
     */
    fun buildSsml(text: String, voice: String, resourceId: String, rate: String): String {
        val voiceBlock = "    <voice name=\"$voice\" mock_tone_info=\"\" platform=\"sami\" " +
            "resource_id=\"$resourceId\" emotion=\"\" emotion_scale=\"0\" style=\"\" role=\"\" " +
            "moyin_emotion=\"\" is_clone_tone=\"false\" need_subtitle_timestamp=\"false\">\n" +
            "        <prosody rate=\"$rate\">${escapeXml(text)}</prosody>\n" +
            "    </voice>"
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"en-US\">\n$voiceBlock\n</speak>"
    }

    /** Chuỗi đầu vào để ký payload TTS (provider mã hóa RSA chuỗi này). */
    fun payloadSignInput(ssmlMd5Hex: String, extraInfo: String, deviceId: String, appId: String): String =
        "appid:$appId&did:$deviceId&creditDisable:false&ssml:$ssmlMd5Hex&extraInfo:$extraInfo"

    /** Chuỗi đầu vào của header sign: md5(9e2c|7 ký tự cuối path|3|appvr|deviceTime|tdid|11ac). */
    fun signHeaderInput(url: String, appVersion: String, deviceTime: String, tdid: String): String {
        val path = url.substringBefore("?")
        val tail = if (path.length >= 7) path.takeLast(7) else path
        return "9e2c|$tail|3|$appVersion|$deviceTime|$tdid|11ac"
    }

    /** Trace id dạng "00-<32 hex>-<16 hex đầu>-01" từ seed hex của UUID bỏ gạch. */
    fun traceId(uuidHex32: String): String {
        val seed = uuidHex32.take(32)
        return "00-$seed-${seed.take(16)}-01"
    }

    /**
     * Query string chung cho cả hai endpoint. [includeRegion] chỉ bật cho task/new;
     * [babiParam] chỉ có ở task/new. Giữ đúng thứ tự khóa của client tham chiếu.
     */
    fun buildCommonQuery(
        device: Map<String, String>,
        babiParam: String?,
        includeRegion: Boolean,
    ): String {
        val query = LinkedHashMap<String, String>()
        for (key in COMMON_QUERY_KEYS) query[key] = device.getValue(key)
        if (includeRegion) query["region"] = device.getValue("region")
        if (babiParam != null) query["babi_param"] = babiParam
        return query.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
    }

    fun objectPayload(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonPrimitive -> value.contentOrNull?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        else -> null
    }

    /**
     * Ordered alternatives for a complete clip. Segment lists with multiple
     * items cannot be fed as a single clip, so accept only a singleton list.
     */
    fun audioUrls(task: JsonObject): List<String> {
        val merged = linkedSetOf<String>()
        val complete = linkedSetOf<String>()
        val singleton = linkedSetOf<String>()

        fun url(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

        fun visit(value: JsonElement?, depth: Int, candidates: MutableSet<String>) {
            if (depth > 6) return
            val obj = objectPayload(value) ?: return
            for (key in listOf("merged_audio_url", "merge_audio_url", "mergedAudioUrl", "full_audio_url")) {
                url(obj[key])?.let(merged::add)
            }
            for (key in listOf("speech_url", "audio_url", "audioUrl", "download_url", "file_url", "url")) {
                url(obj[key])?.let(candidates::add)
            }
            for (key in listOf("payload", "output", "result", "data", "audio")) {
                val nested = obj[key]
                if (key == "audio") url(nested)?.let(candidates::add)
                if (nested is JsonArray) visit(nested.singleOrNull(), depth + 1, singleton)
                else visit(nested, depth + 1, candidates)
            }
            // CapCut returns the finished speech in a combined audio/subtitle record.
            // Only speech_url is audio here. Other URLs may be timed text.
            val speech = (obj["audio_subtitles"] as? JsonArray)?.singleOrNull() as? JsonObject
            url(speech?.get("speech_url"))?.let(singleton::add)
            for (key in listOf("audios", "audio_list", "audio_segments", "audio_urls")) {
                val item = (obj[key] as? JsonArray)?.singleOrNull()
                url(item)?.let(singleton::add)
                visit(item, depth + 1, singleton)
            }
        }
        visit(task, 0, complete)
        return (merged + complete + singleton).toList()
    }

    /** Formats the Android playback pipeline can decode from a complete CapCut file. */
    fun looksLikeSupportedAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        fun at(offset: Int, signature: String) = offset + signature.length <= bytes.size &&
            signature.indices.all { bytes[offset + it] == signature[it].code.toByte() }

        val frameSync = bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
        return at(0, "ID3") || frameSync || (at(0, "RIFF") && at(8, "WAVE")) ||
            (at(0, "OggS") && bytes.size >= 27) || (at(4, "ftyp") && bytes.size >= 12)
    }
}
