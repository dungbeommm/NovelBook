package vn.tramtruyen.reader.engine.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import vn.tramtruyen.reader.engine.Config
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * TTS ngoai tuyen bang engine Android (TextToSpeech) — khong can mang, mac dinh.
 * synthesizeToFile -> WAV bytes. voice_id dang "android:{voiceName}" hoac "android:default".
 */
class AndroidTtsProvider(private val context: Context) : TtsProvider {

    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val synthesisMutex = Mutex()
    @Volatile private var voicesCache: List<JsonObject> = listOf(
        TtsUtil.voice("android:default", "Android 1", "NEUTRAL", "Offline", "vi-VN", "Android TTS", "android", false)
    )

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tts?.language = Locale("vi", "VN") } catch (_: Exception) {}
                    refreshVoices()
                    if (!ready.isCompleted) ready.complete(true)
                } else {
                    if (!ready.isCompleted) ready.complete(false)
                }
            }
        } catch (e: Exception) {
            if (!ready.isCompleted) ready.complete(false)
        }
    }

    private fun refreshVoices() {
        try {
            val engine = tts ?: return
            val out = ArrayList<JsonObject>()
            val engineName = when (engine.defaultEngine.orEmpty().lowercase()) {
                "com.google.android.tts" -> "Google"
                "com.samsung.smt" -> "Samsung"
                else -> "Android"
            }
            out.add(TtsUtil.voice(
                "android:default", "$engineName 1", "NEUTRAL", "Offline",
                "vi-VN", "Android TTS", "android", false,
            ))
            val voices: Set<Voice> = try { engine.voices ?: emptySet() } catch (_: Exception) { emptySet() }
            val vietnamese = voices.filter { voice ->
                val lang = try { voice.locale.language } catch (_: Exception) { "" }
                lang.equals("vi", true) || lang.equals("vie", true)
            }.sortedBy { it.name }
            vietnamese.forEachIndexed { index, voice ->
                val network = try { voice.isNetworkConnectionRequired } catch (_: Exception) { false }
                out.add(TtsUtil.voice(
                    "android:${voice.name}", "$engineName ${index + 2}", "NEUTRAL",
                    if (network) "Online" else "Offline", "vi-VN",
                    "Android TTS", "android", false,
                ))
            }
            voicesCache = out
        } catch (_: Exception) {}
    }

    override val voiceList: List<JsonObject> get() = voicesCache

    override fun isConfigured(): Boolean = true

    override suspend fun synthesize(text: String, voiceId: String, speed: Double, pitch: Double): ByteArray? = synthesisMutex.withLock {
        val ok = withTimeoutOrNull(10_000) { ready.await() } ?: false
        val engine = tts
        if (!ok || engine == null) return null
        val clean = text.trim()
        if (clean.isEmpty()) return null

        try {
            if (voiceId.isNotBlank() && voiceId != "default") {
                val match = engine.voices?.firstOrNull { it.name == voiceId }
                if (match != null) engine.voice = match
            }
            engine.setSpeechRate(TtsUtil.clampSpeed(speed).toFloat())
            engine.setPitch(TtsUtil.clampPitch(pitch).toFloat())
        } catch (_: Exception) {}

        val outFile = File(File(Config.dataDir, "tts_tmp").apply { mkdirs() }, "tts_${System.nanoTime()}.wav")
        val done = CompletableDeferred<Boolean>()
        val uid = "u${System.nanoTime()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == uid && !done.isCompleted) done.complete(true)
            }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) {
                if (utteranceId == uid && !done.isCompleted) done.complete(false)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == uid && !done.isCompleted) done.complete(false)
            }
        })

        val params = Bundle()
        val res = engine.synthesizeToFile(clean, params, outFile, uid)
        if (res != TextToSpeech.SUCCESS) { outFile.delete(); return null }

        val success = withTimeoutOrNull(120_000) { done.await() } ?: false
        return try {
            if (success && outFile.exists() && outFile.length() > 0) outFile.readBytes() else null
        } finally {
            try { outFile.delete() } catch (_: Exception) {}
        }
    }
}
