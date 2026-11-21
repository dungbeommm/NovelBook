package vn.tramtruyen.reader.engine.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Request

/** Zalo AI Vietnamese TTS implemented from the public extension protocol. */
class ZaloTtsProvider(private val apiKey: String) : TtsProvider {
    private val voices = linkedMapOf(
        "1" to Pair("Zalo — Nữ miền Nam", "FEMALE"),
        "2" to Pair("Zalo — Nữ miền Bắc", "FEMALE"),
        "3" to Pair("Zalo — Nam miền Nam", "MALE"),
        "4" to Pair("Zalo — Nam miền Bắc", "MALE"),
    )
    private val json = Json { ignoreUnknownKeys = true }
    override val voiceList: List<JsonObject> = voices.map { (id, info) ->
        TtsUtil.voice("zalo:$id", info.first, info.second, "Zalo AI", "vi-VN", "Zalo AI TTS", "zalo", true)
    }
    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? =
        withContext(Dispatchers.IO) {
            val id = voiceId.removePrefix("zalo:")
            if (!isConfigured() || id !in voices) return@withContext null
            val clean = text.trim().take(500)
            if (clean.isEmpty()) return@withContext null
            try {
                val apiSpeed = (((TtsUtil.clampSpeed(speed) - 1.0) * 2.0).toInt()).coerceIn(-3, 3)
                val body = FormBody.Builder()
                    .add("input", clean).add("encode_type", "mp3")
                    .add("speed", apiSpeed.toString()).add("speaker_id", id).build()
                val req = Request.Builder().url("https://api.zalo.ai/v1/tts/synthesize")
                    .post(body).header("apikey", apiKey.trim()).build()
                val audioUrl = TtsUtil.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val obj = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    if (obj["error_code"]?.jsonPrimitive?.intOrNull != 0) return@withContext null
                    obj["data"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                } ?: return@withContext null
                repeat(5) { attempt ->
                    if (attempt > 0) delay(700L * attempt)
                    val get = Request.Builder().url(audioUrl).build()
                    TtsUtil.http.newCall(get).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
                    }
                }
                null
            } catch (_: Exception) { null }
        }
}
