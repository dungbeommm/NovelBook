package vn.tramtruyen.reader.presentation

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import vn.tramtruyen.reader.model.ColorMode
import vn.tramtruyen.reader.model.DEFAULT_ACCENT
import vn.tramtruyen.reader.model.DEFAULT_SEED
import vn.tramtruyen.reader.model.FrameRateMode
import vn.tramtruyen.reader.model.MONET
import vn.tramtruyen.reader.model.UiTheme
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "nr_md3_v1")

data class PersistedSettings(
    val uiTheme: UiTheme,
    val colorMode: ColorMode,
    val monetSeed: String,
    val accentColor: String,
    val selectedVoice: String,
    val autoNextChapter: Boolean,
    val karaokeFontSize: Int,
    val playerVolume: Float,
    val frameRateMode: FrameRateMode,
    val playerBlur: Int,
    val hapticEnabled: Boolean,
    val hapticIntensity: Int,
    val voiceProfileEnabled: Boolean,
    val contentLanguage: String,
    val contentExtractionMode: String,
    val disabledTtsSources: Set<String> = emptySet(),
)

internal fun resolveColorMode(storedName: String?, legacyMonet: Boolean): ColorMode = when (storedName) {
    ColorMode.CUSTOM_SEED.name -> ColorMode.CUSTOM_SEED
    ColorMode.COVER_ART.name -> ColorMode.COVER_ART
    ColorMode.SYSTEM_WALLPAPER.name -> ColorMode.SYSTEM_WALLPAPER
    // Bao gồm giá trị cũ "DEFAULT", null và dữ liệu lạ.
    else -> if (legacyMonet) ColorMode.CUSTOM_SEED else ColorMode.SYSTEM_WALLPAPER
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("uiTheme")
        val monet = booleanPreferencesKey("monetTheme") // legacy
        val colorMode = stringPreferencesKey("colorMode")
        val seed = stringPreferencesKey("monetSeed")
        val accent = stringPreferencesKey("accentColor")
        val voice = stringPreferencesKey("selectedVoice")
        val autoNext = booleanPreferencesKey("autoNextChapter")
        val karaokeFont = intPreferencesKey("karaokeFontSize")
        val playerVolume = floatPreferencesKey("playerVolume")
        val frameRate = stringPreferencesKey("frameRateMode")
        val playerBlur = intPreferencesKey("playerBlur")
        val hapticEnabled = booleanPreferencesKey("hapticEnabled")
        val hapticIntensity = intPreferencesKey("hapticIntensity")
        val voiceProfile = booleanPreferencesKey("voiceProfileEnabled")
        val contentLanguage = stringPreferencesKey("contentLanguage")
        val contentExtractionMode = stringPreferencesKey("contentExtractionMode")
        val disabledSources = stringSetPreferencesKey("disabledTtsSources")
    }

    suspend fun load(): PersistedSettings {
        val p = context.dataStore.data.first()
        // Di trú cả giá trị cũ "DEFAULT" sang màu hình nền; app không còn
        // bảng màu mặc định cố định.
        val colorMode = resolveColorMode(
            storedName = p[Keys.colorMode],
            legacyMonet = p[Keys.monet] == true,
        )
        return PersistedSettings(
            uiTheme = runCatching { UiTheme.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(UiTheme.SYSTEM),
            colorMode = colorMode,
            monetSeed = (p[Keys.seed] ?: DEFAULT_SEED).takeIf { it in MONET } ?: DEFAULT_SEED,
            accentColor = p[Keys.accent] ?: DEFAULT_ACCENT,
            selectedVoice = vn.tramtruyen.reader.engine.speech.TtsVoicePolicy.migrate(p[Keys.voice]),
            autoNextChapter = p[Keys.autoNext] ?: true,
            karaokeFontSize = p[Keys.karaokeFont] ?: 24,
            playerVolume = (p[Keys.playerVolume] ?: 1f).coerceIn(0f, 1f),
            frameRateMode = runCatching {
                FrameRateMode.valueOf(p[Keys.frameRate] ?: "AUTO")
            }.getOrDefault(FrameRateMode.AUTO),
            playerBlur = (p[Keys.playerBlur] ?: 8).coerceIn(0, 60),
            hapticEnabled = p[Keys.hapticEnabled] ?: true,
            hapticIntensity = (p[Keys.hapticIntensity] ?: 55).coerceIn(1, 100),
            voiceProfileEnabled = p[Keys.voiceProfile] ?: true,
            contentLanguage = p[Keys.contentLanguage] ?: "AUTO",
            contentExtractionMode = p[Keys.contentExtractionMode] ?: "SMART",
            disabledTtsSources = (p[Keys.disabledSources] ?: emptySet())
                .filterNot { it.equals("offline", ignoreCase = true) }.toSet(),
        )
    }

    suspend fun save(s: UiState) {
        context.dataStore.edit { e ->
            e[Keys.theme] = s.uiTheme.name
            e[Keys.colorMode] = s.colorMode.name
            e[Keys.monet] = true // legacy key; mọi mode hiện tại đều dùng màu động
            e[Keys.seed] = s.monetSeed.takeIf { it in MONET } ?: DEFAULT_SEED
            e[Keys.accent] = s.accentColor
            e[Keys.voice] = s.selectedVoice
            e[Keys.autoNext] = s.autoNextChapter
            e[Keys.karaokeFont] = s.karaokeFontSize
            e[Keys.playerVolume] = s.playerVolume.coerceIn(0f, 1f)
            e[Keys.frameRate] = s.frameRateMode.name
            e[Keys.playerBlur] = s.playerBlur.coerceIn(0, 60)
            e[Keys.hapticEnabled] = s.hapticEnabled
            e[Keys.hapticIntensity] = s.hapticIntensity.coerceIn(1, 100)
            e[Keys.voiceProfile] = s.voiceProfileEnabled
            e[Keys.contentLanguage] = s.contentLanguage
            e[Keys.contentExtractionMode] = s.contentExtractionMode
            e[Keys.disabledSources] = s.disabledTtsSources
        }
    }
}
