package vn.tramtruyen.reader.engine.catalog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cong tac bat/tat cho cac luot TAI NGOAI cua app (them o ban 1.007 theo yeu cau
 * cua Cruz: "them cong tac tuy chon tai bat tat vao app"). Theo dung mau cua
 * AdBlockManager: SharedPreferences rieng + StateFlow cho man Cai dat doc, cac
 * diem tai doc snapshot dong bo nen khong can coroutine.
 *
 * Tải model giọng đọc Piper và dữ liệu eSpeak có thể bị tắt; model đã tải vẫn dùng được.
 */
object DownloadOptions {
    data class State(
        val voiceModelsEnabled: Boolean = true,
    )

    private const val PREFS = "download_options"
    private const val KEY_VOICE_MODELS = "voice_models_enabled"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = State(
            voiceModelsEnabled = prefs.getBoolean(KEY_VOICE_MODELS, true),
        )
    }

    fun setVoiceModelsEnabled(context: Context? = appContext, enabled: Boolean) {
        context ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VOICE_MODELS, enabled)
            .apply()
        _state.update { it.copy(voiceModelsEnabled = enabled) }
    }

    /** Diem tai model giong doc doc khoa nay truoc khi cham mang. */
    fun voiceModelsAllowed(): Boolean = _state.value.voiceModelsEnabled
}
