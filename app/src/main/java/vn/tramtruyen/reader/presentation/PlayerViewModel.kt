package vn.tramtruyen.reader.presentation

import android.app.Application
import android.net.Uri
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import vn.tramtruyen.reader.model.*
import vn.tramtruyen.reader.presentation.theme.hex
import vn.tramtruyen.reader.TramTruyenApplication
import vn.tramtruyen.reader.ReadingMediaBridge
import vn.tramtruyen.reader.ReadingPlaybackService
import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.ReadingEngine
import vn.tramtruyen.reader.engine.ReadingPreferences
import vn.tramtruyen.reader.engine.Store
import vn.tramtruyen.reader.engine.UrlPolicy
import vn.tramtruyen.reader.engine.sources.GenericExtractor
import vn.tramtruyen.reader.engine.speech.CapcutTtsProtocol
import vn.tramtruyen.reader.engine.speech.GeminiTtsProvider
import vn.tramtruyen.reader.engine.speech.ZaloTtsProvider
import vn.tramtruyen.reader.engine.speech.OfflineTtsModelManager
import vn.tramtruyen.reader.engine.speech.TtsManager
import vn.tramtruyen.reader.engine.speech.OfflineVoicePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val s get() = _ui.value

    // Hot playback clock projected to a tiny state. Only the two progress leaves
    // collect it; the app shell observes contentUi and is never invalidated by ticks.
    // Dedicated hot clock: progress can update smoothly without touching the
    // large UiState, app shell, lists, or media notification every frame.
    private val _progressUi = MutableStateFlow(PlaybackProgressState())
    val progressUi: StateFlow<PlaybackProgressState> = _progressUi.asStateFlow()
    private val contentProjectionDispatcher = Dispatchers.Default.limitedParallelism(1)

    // Content-only projection of the UI state (root fix for C1).
    // The tab screens (Home/Library/Search/Settings) never read the live
    // position/duration/progress/playback fields, yet they used to recompose on
    // every ~1s position tick because the whole UiState changed. Zero those
    // volatile fields out + distinctUntilChanged so this flow only re-emits on
    // real content changes. With Kotlin 2.0.20 strong-skipping, the tab screens
    // then skip recomposition entirely while audio plays.
    val contentUi: StateFlow<UiState> = _ui
        .map {
            it.copy(
                positionMs = 0L,
                durationMs = 0L,
                progress = 0f,
                // Keep stable controls/metadata for mini-player and player buttons;
                // strip only the hot clock values that change during playback.
                playback = it.playback.copy(positionMs = 0L, durationMs = 0L),
            )
        }
        .distinctUntilChanged()
        // copy/equals của UiState lớn chạy ngoài Main. Dù kết quả bị distinct loại
        // bỏ, phép chiếu mỗi lần metadata phát cập nhật không được lấy frame UI.
        .flowOn(contentProjectionDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val store = SettingsStore(app)
    private val smartStore = SmartFeaturesStore(app)
    private val epubImporter = EpubImportUseCase(app)
    private var stopAfterSentence = false
    private var stopAfterChapter = false
    private var stopAfterSentenceAt = -1
    /** Seek/doi giong trong luc pause: engine con giu audio o vi tri/giong cu,
     *  nen resume() phai speakFrom() lai thay vi play() tiep buffer cu. */
    private var restartOnResume = false
    private var playToken = 0
    private val audioCache = LruByteCache()
    private val pcmCache = LruPcmCache()
    /** Cùng một chapter/index/voice chỉ được tổng hợp một lần dù feeder và prefetch gặp nhau. */
    private val synthInFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    /** Huy o phia chu so huu khong duoc nem thang CancellationException vao cac
     *  ben dang await() chung mot deferred: no se giet ca feeder dang chay. */
    private class SharedSynthCancelled(cause: Throwable) : Exception("shared synth cancelled", cause)
    private val chunkDurations = HashMap<Int, Long>() // index -> ms
    private var voiceSwitchJob: Job? = null
    private var volumeRampJob: Job? = null
    @Volatile private var voiceFadeInPending = false

    // Single gapless PCM playback engine.
    /** So doan PCM giu san trong hang doi cua engine. */
    private val pcmQueueDepth = 4
    /** Engine da khoi tao chua - tranh tao thread khi chua he phat gi. */
    private var pcmActive = false
    private var feedJob: Job? = null
    /**
     * User bam "Dung" tren thanh media -> tat notification cho den lan phat tiep.
     * Khong dung flag nay thi collector o ReaderActivity se start lai service ngay
     * vi activeStory van con, nut Dung tro thanh vo nghia.
     */
    @Volatile private var mediaSessionDismissed = false

    private val mediaCommands = object : ReadingMediaBridge.Commands {
        override fun play() = togglePlayIfPaused()
        override fun pause() = pauseFromMedia()
        override fun stop() = stopFromMedia()
        override fun next() = nextChapter()
        override fun prev() = prevChapter()
        override fun seekTo(positionMs: Long) = seekToMediaPosition(positionMs)
    }
    private val pcmEngine: GaplessAudioEngine by lazy {
        GaplessAudioEngine(
            onChunkStarted = { index -> onPcmChunkStarted(index) },
            onDrained = { index -> onPcmDrained(index) },
            onFatal = { _ -> onPcmFatal() },
        )
    }

    // Fix 2: next-chapter warm cache (content prefetched into ReadingEngine + first-chunk audio).
    private data class WarmedChapter(
        val chapterUrl: String,
        val firstChunkText: String,
        val voice: String,
        val bytes: ByteArray?,
        val decoded: DecodedPcm?,
        val responseJson: String,
    )
    private data class WarmedAudio(val bytes: ByteArray, val decoded: DecodedPcm?)
    /**
     * Kho warm theo URL chuong. Truoc day chi giu DUY NHAT chuong ke tiep, nen
     * bam Prev la mat sach phan da chuan bi cho chuong sau.
     */
    private val warmEpoch = java.util.concurrent.atomic.AtomicLong(0)
    private val warmedChapters = java.util.concurrent.ConcurrentHashMap<String, WarmedChapter>()
    /** Task audio đầu chương dùng chung giữa warm nền và feeder sau chuyển chương. */
    private val warmAudioTasks = java.util.concurrent.ConcurrentHashMap<String, Deferred<WarmedAudio?>>()
    /**
     * Cac URL dang warm dở. Thay cho `warmedForSig` cu: co cu duoc GO RA khi that
     * bai, nen mot lan fetch loi khong con khoa vinh vien viec warm chuong do.
     */
    private val warmingUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())
    /** So cu bam Next/Prev bi don lai trong luc dang tai chuong (duong: next, am: prev). */
    // Cached prefix-sum of chunk start offsets (ms). Recomputed only when
    // sentences / speed / known chunk durations change, so the 500ms ticker no
    // longer loops over every sentence on the main thread each tick (fix C2).
    private var chunkOffsets: LongArray = LongArray(1)
    @Volatile private var offsetsDirty = true
    // Ticker throttle bookkeeping (fix C1 hot path).
    private var lastTickPosSec = -1L
    private var lastTickFracBucket = -1
    // Exact PCM durations arrive asynchronously and rebuild prefix offsets. Keep
    // the visual clock monotonic within one playback token so the bar never moves
    // forward and then backward when an estimate is replaced by a real duration.
    private var progressClockToken = -1
    private var progressFloorMs = 0L
    private var progressFloorFraction = 0f
    private var chapterSig = ""
    private var backendReady = false
    private var sleepSeconds = 0
    private var progressJob: Job? = null
    private var persistJob: Job? = null
    private var chapterLoadJob: Job? = null
    private var retryChapterLoad: (() -> Unit)? = null
    private var webSearchJob: Job? = null
    private var autoSearchJob: Job? = null
    private var openFirstChapterJob: Job? = null

    /** Cho nguoi dung ngung go bao lau roi moi ban ra web (tranh tim moi phim). */
    private val AUTO_SEARCH_DEBOUNCE_MS = 320L

    /**
     * Khoang cach toi thieu giua hai lan ve lai danh sach ket qua.
     *
     * 72 nguon tra ve rai rac, neu nguon nao ve cung ve lai giao dien ngay thi danh
     * sach nhay lien tuc va cuon bi khuc khuu. 160ms du de cam giac "hien dan" ma
     * khong lam roi mat.
     */
    private val PARTIAL_UI_MIN_GAP_MS = 900L

    /** So dong ket qua toi da giu lai - dai hon nua chi lam giao dien nang them. */
    private val MAX_WEB_RESULTS = 40

    /** Moc thoi gian lan cuoi day ket qua tam thoi len giao dien. */
    private var lastPartialPushAt = 0L

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    // Chi nguoi dung moi duoc tam dung: nut tam dung o mini-player, o player, hoac
    // nut tren notification / tai nghe (media session cua Android).
    // Moi su kien he thong khac (mat audio focus vi thong bao, chuong bao, app khac
    // phat am thanh ngan...) chi HA AM LUONG roi tra lai, KHONG BAO GIO tu tam dung.
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (pcmActive) pcmEngine.setVolume(s.playerVolume * 0.3f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pcmActive) pcmEngine.setVolume(s.playerVolume)
            }
        }
    }

    init {
        // Dang ky SOM de MediaSession cua ReadingPlaybackService goi thang vao day,
        // khong can mo Activity (bi Android chan khi app o nen).
        ReadingMediaBridge.register(mediaCommands)
        loadPersisted()
        bootstrapBackend()
        startSleepTimer()
        startPositionTicker()
    }

    // ---------- persistence ----------
    private fun loadPersisted() = viewModelScope.launch {
        val p = store.load()
        _ui.update {
            it.copy(
                uiTheme = p.uiTheme,
                colorMode = p.colorMode,
                monetTheme = true,
                monetSeed = p.monetSeed,
                accentColor = p.accentColor,
                selectedVoice = p.selectedVoice,
                autoNextChapter = p.autoNextChapter,
                karaokeFontSize = p.karaokeFontSize,
                playerVolume = p.playerVolume,
                frameRateMode = p.frameRateMode,
                playerBlur = p.playerBlur,
                hapticEnabled = p.hapticEnabled,
                hapticIntensity = p.hapticIntensity,
                voiceProfileEnabled = p.voiceProfileEnabled,
                contentLanguage = p.contentLanguage,
                contentExtractionMode = p.contentExtractionMode,
                disabledTtsSources = p.disabledTtsSources,
                pronunciationRules = smartStore.rules(),
            )
        }
        ReadingPreferences.update(p.contentLanguage, p.contentExtractionMode)
        TtsManager.setDisabledProviders(p.disabledTtsSources)
        loadVoices()
    }

    private fun persist() = viewModelScope.launch { store.save(s) }

    // ---------- backend ----------
    private suspend fun ensureReady() {
        if (backendReady) return
        withContext(Dispatchers.IO) {
            try { TramTruyenApplication.awaitReady() } catch (_: Exception) {}
        }
        backendReady = true
    }

    private fun bootstrapBackend() = viewModelScope.launch(Dispatchers.IO) {
        ensureReady()
        runCatching { Store.migrateSchema() }
        // Khoa Gemini nam trong SharedPreferences "tts_keys" (TramTruyenApplication da nap vao
        // Config luc khoi dong); day chi la buoc dong bo len giao dien Cai dat.
        val app = getApplication<Application>()
        val savedGemini = TramTruyenApplication.geminiApiKey(app)
        val savedZalo = TramTruyenApplication.zaloApiKey(app)
        _ui.update {
            it.copy(
                geminiApiKey = savedGemini,
                geminiKeyOk = savedGemini.isNotBlank(),
                zaloApiKey = savedZalo,
                zaloKeyOk = savedZalo.isNotBlank(),
            )
        }
        refreshOfflineRuntime()
        loadVoices()
        refreshOfflineModels()
        refreshCollections()
        restoreLastSession()
    }

    private fun loadVoices() {
        try {
            val root = JSONObject(ReadingEngine.ttsVoices())
            val arr = root.optJSONArray("voices") ?: return
            val groups = LinkedHashMap<String, MutableList<VoiceOption>>()
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val id = v.optString("voice_id"); if (id.isBlank()) continue
                val provider = v.optString("provider", "Khác").ifBlank { "Khác" }
                val name = v.optString("name", id)
                val sub = listOf(genderLabel(v.optString("gender", "")), v.optString("tier", ""))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                groups.getOrPut(provider) { mutableListOf() }.add(VoiceOption(id, name, sub))
            }
            val ordered = groups.entries
                .sortedBy {
                    when {
                        it.key.contains("Google", true) -> 0
                        it.key.contains("Edge", true) -> 1
                        it.key.contains("Android", true) -> 2
                        else -> 3
                    }
                }
                .map { VoiceGroup(it.key, it.value) }
            _ui.update { it.copy(voiceGroups = ordered) }
        } catch (_: Exception) {}
        refreshTtsSources()
    }

    private fun genderLabel(g: String): String = when (g.uppercase()) {
        "MALE" -> "Nam"; "FEMALE" -> "Nữ"; "NEUTRAL" -> "Trung tính"; else -> ""
    }


    private fun refreshOfflineRuntime(message: String? = null) {
        val ready = OfflineTtsModelManager.runtimeReady()
        _ui.update { it.copy(offlineRuntimeReady = ready,
            offlineRuntimeStatus = message ?: it.offlineRuntimeStatus) }
    }

    fun installOfflineRuntime() {
        if (s.offlineRuntimeInstalling || s.offlineRuntimeReady) return
        _ui.update { it.copy(offlineRuntimeInstalling = true, offlineRuntimeProgress = 0,
            offlineRuntimeStatus = "Đang tải tài nguyên trình chạy TTS…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                OfflineTtsModelManager.prepareSharedData { progress ->
                    _ui.update { it.copy(offlineRuntimeProgress = progress,
                        offlineRuntimeStatus = if (progress < 88) "Đang tải tài nguyên · $progress%" else "Đang cài dữ liệu phát âm…") }
                }
                refreshOfflineRuntime("Trình chạy TTS đã sẵn sàng")
            } catch (e: Exception) {
                _ui.update { it.copy(offlineRuntimeStatus = "Không cài được trình chạy: ${e.message}") }
            } finally {
                _ui.update { it.copy(offlineRuntimeInstalling = false) }
            }
        }
    }

    fun importOfflineVoice(uri: Uri) {
        if (s.offlineTtsBusyId != null || !s.offlineRuntimeReady) return
        _ui.update { it.copy(offlineTtsBusyId = "__import__", offlineTtsStatus = "Đang kiểm tra ZIP model…") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = OfflineTtsModelManager.importFromUri(getApplication<Application>(), uri)
            refreshOfflineModels(); loadVoices()
            _ui.update { it.copy(offlineTtsBusyId = null,
                offlineTtsStatus = result.fold({ "Đã nhập giọng ${it.name}" }, { "Không nhập được: ${it.message}" })) }
        }
    }

    private fun refreshOfflineModels() {
        val list = runCatching {
            OfflineTtsModelManager.models().map { m ->
                OfflineVoiceModel(
                    id = m.id, name = m.name,
                    gender = genderLabel(m.gender),
                    sizeMb = (m.sizeBytes / 1024 / 1024).toInt(),
                    installed = OfflineTtsModelManager.isInstalled(m.id),
                    source = m.source,
                )
            }
        }.getOrDefault(emptyList())
        _ui.update { it.copy(offlineVoiceModels = list) }
    }

    fun installOfflineVoice(id: String) {
        if (s.offlineTtsBusyId != null) return
        _ui.update { it.copy(offlineTtsBusyId = id, offlineTtsProgress = 0,
            offlineTtsStatus = "Đang chuẩn bị dữ liệu phát âm…") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = OfflineTtsModelManager.install(id) { progress ->
                _ui.update { state -> state.copy(
                    offlineTtsProgress = progress,
                    offlineTtsStatus = if (progress < 92) "Đang tải $progress%" else "Đang kiểm tra và giải nén…",
                ) }
            }
            refreshOfflineModels()
            loadVoices()
            if (result.isSuccess) withContext(Dispatchers.Main) { setVoice("offline:$id") }
            _ui.update { it.copy(
                offlineTtsBusyId = null,
                offlineTtsProgress = if (result.isSuccess) 100 else 0,
                offlineTtsStatus = if (result.isSuccess) "Đã cài giọng ngoại tuyến" else
                    "Không cài được: ${result.exceptionOrNull()?.message ?: "lỗi không xác định"}",
            ) }
        }
    }

    fun deleteOfflineVoice(id: String) {
        if (s.offlineTtsBusyId != null) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                // Stop current/prefetched playback before removing its model.
                if (s.selectedVoice == "offline:$id") stopPlayback()
            }
            val deleted = TtsManager.deleteOfflineModel(id)
            if (!deleted) {
                _ui.update { it.copy(offlineTtsStatus = "Không xóa được model") }
                return@launch
            }
            val wasSelected = s.selectedVoice == "offline:$id"
            if (wasSelected) _ui.update { it.copy(selectedVoice = TtsManager.DEFAULT_VOICE) }
            refreshOfflineModels(); loadVoices()
            if (wasSelected) persist()
            _ui.update { it.copy(offlineTtsStatus = "Đã xóa model") }
        }
    }

    private fun refreshCollections() {
        val history = parseItems(runCatching { ReadingEngine.historyGet() }.getOrNull())
        val library = parseItems(runCatching { ReadingEngine.libraryGet() }.getOrNull())
        _ui.update {
            it.copy(historyItems = history, libraryItems = library)
        }
    }

    private fun restoreLastSession() {
        val last = s.historyItems.firstOrNull() ?: return
        if (last.id == placeholderStory.id) return
        val prog = Store.getProgress(last.id)
        fun pstr(key: String): String =
            (prog?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
        val sentenceIdx = pstr("sentenceIndex").toIntOrNull() ?: last.sentenceIndex
        val chapterUrl = pstr("chapterUrl").ifBlank { last.chapterUrl }
        val chapterTitle = pstr("chapterTitle").ifBlank { last.chapterTitle }
        val chapterIndex = pstr("chapterIndex").toIntOrNull() ?: last.currentChapter
        val progress = pstr("progress").toFloatOrNull() ?: last.progress
        _ui.update {
            it.copy(
                activeStory = last.copy(
                    chapterUrl = chapterUrl,
                    sentenceIndex = sentenceIdx,
                    chapterTitle = chapterTitle,
                    currentChapter = chapterIndex,
                    progress = progress,
                ),
                chapterNumber = chapterIndex,
                chapterTitle = chapterTitle,
                activeSentenceIndex = sentenceIdx,
                progress = progress,
            )
        }
        publishPlayback()
    }

    private fun parseItems(json: String?): List<Story> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { storyFromJson(it) } }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseStories(json: String?): List<Story> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("stories") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { storyFromJson(it) } }
        } catch (_: Exception) { emptyList() }
    }

    private fun storyFromJson(o:JSONObject):Story{
        fun str(vararg k:String):String{k.forEach{val v=o.optString(it,"");if(v.isNotBlank()&&v!="null")return v};return ""}
        val chapter=str("chapterUrl","chapter_url").ifBlank{null};val storyUrl=str("storyUrl","story_url","tocUrl","toc_url").ifBlank{null};val local=str("localBookId","epubId").ifBlank{null}
        val type=runCatching{StorySourceType.valueOf(str("sourceType").ifBlank{if(local!=null||chapter?.startsWith("epub://")==true)"EPUB" else "ONLINE"})}.getOrDefault(StorySourceType.ONLINE)
        val title=str("title","story_title").ifBlank{"Truyện"};val source=str("source").ifBlank{if(type==StorySourceType.EPUB)"EPUB ngoại tuyến" else StoryIds.sourceLabel(storyUrl?:chapter)}
        val id=if(type==StorySourceType.EPUB)(str("id").ifBlank{local?:"epub"})else StoryIds.storyId(str("id").ifBlank{null},storyUrl,chapter,str("folder").ifBlank{null},title,source)
        val tags=o.optJSONArray("tags")?.let{a->(0 until a.length()).map{a.optString(it)}}?:emptyList()
        return Story(id,title,str("author").ifBlank{source},str("coverUrl","cover_url"),o.optInt("totalChapters",o.optInt("chapters",0)),tags,str("color").ifBlank{DEFAULT_ACCENT},o.optInt("currentChapter",o.optInt("chapterIndex",1)).coerceAtLeast(1),source,type,local,str("localFileName","fileName").ifBlank{null},o.optLong("fileSizeBytes",0),if(type==StorySourceType.EPUB)storyUrl else storyUrl?:StoryIds.guessStoryUrlFromChapter(chapter?:"").ifBlank{null},chapter,str("nextUrl","next_url").ifBlank{null},str("prevUrl","prev_url").ifBlank{null},str("chapterTitle","chapter_title"),o.optInt("sentenceIndex",0),o.optInt("sentenceCount",0),o.optDouble("progress",0.0).toFloat(),o.optLong("positionMs",0),o.optLong("updatedAt",0),o.optLong("lastListenedAt",o.optLong("updatedAt",0))).copy(
            mirrorUrls=(o.optJSONArray("mirrorUrls")?:org.json.JSONArray()).let{a->(0 until a.length()).map{a.optString(it)}.filter(String::isNotBlank)}
        )
    }
    private fun storyToJson(st:Story):String=JSONObject().apply{
        put("id",st.id);put("title",st.title);put("author",st.author);put("coverUrl",st.coverUrl);put("color",st.color);put("totalChapters",st.totalChapters);put("currentChapter",st.currentChapter);put("chapterIndex",st.currentChapter);put("chapterTitle",st.chapterTitle);put("sentenceIndex",st.sentenceIndex);put("sentenceCount",st.sentenceCount);put("progress",st.progress.toDouble());put("positionMs",st.positionMs);put("updatedAt",st.updatedAt);put("lastListenedAt",st.lastListenedAt);put("sourceType",st.sourceType.name);put("fileSizeBytes",st.fileSizeBytes);put("aliases",org.json.JSONArray(StoryIds.aliases(st.id,st.storyUrl,st.chapterUrl,st.localBookId,st.title,st.source)));st.source?.let{put("source",it)};st.localBookId?.let{put("localBookId",it)};st.localFileName?.let{put("localFileName",it)};st.storyUrl?.let{put("storyUrl",it)};put("mirrorUrls",org.json.JSONArray(st.mirrorUrls));st.chapterUrl?.let{put("chapterUrl",it)};st.nextUrl?.let{put("nextUrl",it)};st.prevUrl?.let{put("prevUrl",it)}
    }.toString()
    private fun storyIdentity(st:Story)=buildJsonObject{put("id",JsonPrimitive(st.id));st.storyUrl?.let{put("storyUrl",JsonPrimitive(it))};st.chapterUrl?.let{put("chapterUrl",JsonPrimitive(it))};st.localBookId?.let{put("localBookId",JsonPrimitive(it))};put("title",JsonPrimitive(st.title));put("source",JsonPrimitive(st.source.orEmpty()));put("aliases",JsonArray(StoryIds.aliases(st.id,st.storyUrl,st.chapterUrl,st.localBookId,st.title,st.source).map{JsonPrimitive(it)}))}
    private fun sameStory(first: Story, second: Story): Boolean {
        val firstAliases = StoryIds.aliases(
            first.id, first.storyUrl, first.chapterUrl, first.localBookId, first.title, first.source
        ).map { it.lowercase().trimEnd('/') }.toSet()
        val secondAliases = StoryIds.aliases(
            second.id, second.storyUrl, second.chapterUrl, second.localBookId, second.title, second.source
        ).map { it.lowercase().trimEnd('/') }.toSet()
        return firstAliases.intersect(secondAliases).isNotEmpty()
    }

    // ---------- derived ----------
    fun currentSeedColor(): Color = when (s.colorMode) {
        ColorMode.CUSTOM_SEED,
        ColorMode.SYSTEM_WALLPAPER -> hex(MONET[s.monetSeed] ?: DEFAULT_ACCENT)
        ColorMode.COVER_ART -> hex(
            s.activeStory?.color?.takeIf { it.isNotBlank() }
                ?: (MONET[s.monetSeed] ?: DEFAULT_ACCENT)
        )
    }

    fun allStories(): List<Story> {
        val all = (s.libraryItems + s.historyItems)
            .distinctBy { it.id }
            .filter { it.id != placeholderStory.id }
        return all.ifEmpty { mockStories }
    }

    fun findStory(id: String): Story? = allStories().firstOrNull { it.id == id }

    fun libraryStories(): List<Story> {
        val active=s.activeStory;val base=s.libraryItems.map{if(active!=null&&it.id==active.id)active else it}
        return when (s.libFilter) {
            "reading" -> base.filter { it.lastListenedAt>0L||it.progress>0f||it.sentenceIndex>0||it.chapterTitle.isNotBlank() }
            "completed" -> base.filter { it.totalChapters in 1..it.currentChapter && it.progress >= 0.95f }
            else -> base.distinctBy { it.id }
        }
    }

    fun historyStories(): List<Story> = s.historyItems.filter { it.id != placeholderStory.id }
    fun trendingStories(): List<Story> = allStories().filter { it.id != placeholderStory.id }.take(3)

    // ---------- navigation ----------
    fun setTab(tab: Tab) = _ui.update {
        it.copy(currentTab = tab, tabHistory = (it.tabHistory + tab).takeLast(10))
    }

    fun setLibFilter(f: String) = _ui.update { it.copy(libFilter = f) }
    fun setChapterFilter(f: ChapterFilter) = _ui.update { it.copy(chapterFilter = f) }

    fun goBack() {
        if (s.isPlayerOpen) { closePlayer(); return }
        if (s.sheet != null) { closeSheet(); return }
        if (s.pasteOpen) { closePaste(); return }
        val hist = s.tabHistory.dropLast(1)
        if (hist.isNotEmpty()) _ui.update { it.copy(currentTab = hist.last(), tabHistory = hist) }
    }

    // ---------- player open/close ----------
    fun openPlayer(story: Story) {
        val url = story.chapterUrl ?: story.storyUrl
        if (story.id == placeholderStory.id || url == null) { openPaste(); return }
        // Chon DUNG truyen dang nghe (bam mini-player, bam lai trong thu vien,
        // vuot len mo player...) -> chi mo giao dien, KHONG nap lai chuong va
        // KHONG dung tieng. Am thanh chay lien mach tuyet doi.
        val cur = s.activeStory
        val hasOpenChapter = s.sentences.isNotEmpty() || s.isBuffering || s.chapterLoadError != null
        if (cur?.id == story.id && hasOpenChapter) {
            _ui.update { it.copy(isPlayerOpen = true, hiddenMiniPlayerStoryId = null, sheet = null) }
            return
        }
        persistProgress()
        val resumeIdx = story.sentenceIndex
        _ui.update {
            it.copy(
                activeStory = story,
                isPlayerOpen = true,
                hiddenMiniPlayerStoryId = null,
                sheet = null,
                isKaraoke = false,
                activeSentenceIndex = resumeIdx,
                chapterNumber = story.currentChapter,
                chapterTitle = story.chapterTitle,
            )
        }
        // Chon truyen KHAC -> doc luon truyen vua chon.
        loadChapter(url, autoPlay = true, baseStory = story, resumeSentence = resumeIdx)
    }

    fun browserUrl(): String = vn.tramtruyen.reader.engine.BrowserOpenPolicy.resolve(
        chapterUrl = s.activeStory?.chapterUrl, storyUrl = s.activeStory?.storyUrl,
    )

    fun closePlayer(){persistProgress();_ui.update{it.copy(isPlayerOpen=false,hiddenMiniPlayerStoryId=null)}}
    fun hideMiniPlayer() = _ui.update { it.copy(hiddenMiniPlayerStoryId = it.activeStory?.id) }

    /** Hold-drag down on the mini-player: stop audio playback and dismiss the card. */

    fun toggleKaraoke() = _ui.update { it.copy(isKaraoke = !it.isKaraoke) }

    /**
     * Phát ngay trang chương đang mở trong BrowserSession.
     * Player mở thẳng ở chế độ lời truyện (karaoke), không qua hộp dán liên kết.
     */
    fun listenFromBrowser(rawUrl: String, chapterResponse: String) {
        val url = UrlPolicy.normalizeHttps(rawUrl) ?: return
        val chapter = JSONObject(chapterResponse)
        val source = StoryIds.sourceLabel(url)
        val storyUrl = StoryIds.guessStoryUrlFromChapter(url).ifBlank { null }
        val storyId = StoryIds.storyId(storyUrl = storyUrl, chapterUrl = url)
        val title = chapter.optString("story_title").ifBlank {
            GenericExtractor.cleanStoryTitle(findStory(storyId)?.title.orEmpty(), chapter.optString("title"))
        }
        val story = Story(
            id = storyId,
            title = title.ifBlank { "Truyện từ trình duyệt" },
            author = source,
            coverUrl = "",
            totalChapters = 0,
            tags = listOf("Trình duyệt"),
            color = DEFAULT_ACCENT,
            currentChapter = chapterNumberFromUrl(url) ?: 1,
            source = source,
            storyUrl = storyUrl,
            chapterUrl = url,
            chapterTitle = chapter.optString("title"),
        )
        persistProgress()
        _ui.update {
            it.copy(
                activeStory = story,
                isPlayerOpen = true,
                hiddenMiniPlayerStoryId = null,
                sheet = null,
                isKaraoke = true,
                activeSentenceIndex = 0,
                chapterNumber = story.currentChapter,
                chapterTitle = story.chapterTitle,
                errorMessage = null,
            )
        }
        loadChapter(
            url = url,
            autoPlay = true,
            baseStory = story,
            resumeSentence = 0,
            chapterNumberHint = story.currentChapter,
            browserChapter = chapterResponse,
        )
    }

    private suspend fun findBackupChapter(story: Story, chapterNumber: Int): Pair<String,String>? {
        for (mirror in story.mirrorUrls.filter(String::isNotBlank).distinct()) {
            try {
                val toc=JSONObject(ReadingEngine.toc(mirror));val arr=toc.optJSONArray("chapters")?:continue
                val items = (0 until arr.length()).mapNotNull(arr::optJSONObject)
                val item = items.firstOrNull { it.optInt("index", 0) == chapterNumber }
                    ?: arr.optJSONObject(chapterNumber - 1)?.takeIf { page -> page.optInt("index", 0) <= 0 }
                val url=item?.optString("url").orEmpty()
                if(url.isNotBlank()) return mirror to url
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {}
        }
        return null
    }

    // ---------- chapter loading ----------
    private fun loadChapter(
        url: String,
        autoPlay: Boolean,
        baseStory: Story? = null,
        resumeSentence: Int = 0,
        chapterDelta: Int = 0,
        chapterNumberHint: Int? = null,
        allowSourceFallback: Boolean = true,
        browserChapter: String? = null,
    ) {
        chapterLoadJob?.cancel()
        retryChapterLoad = null
        val previous = s
        val requestedStory = baseStory ?: previous.activeStory
        val knownTitle = requestedStory?.takeIf { it.chapterUrl == url }?.chapterTitle.orEmpty()
        val epubRef = parseEpubRef(url)
        val requestedNumber = epubRef?.second?.plus(1) ?: chapterNumberHint
            ?: chapterNumberFromUrl(url)
            ?: ((requestedStory?.currentChapter ?: previous.chapterNumber) + chapterDelta).coerceAtLeast(1)
        voiceSwitchJob?.cancel()
        voiceSwitchJob = null
        volumeRampJob?.cancel()
        volumeRampJob = null
        voiceFadeInPending = false
        // Fix A: cat tieng chuong cu NGAY LAP TUC.
        // Truoc day releasePlayer() chi chay SAU khi tai xong noi dung, nen
        // AudioTrack van con buffer + toi pcmQueueDepth doan da submit: nguoi nghe
        // tiep tuc nghe chuong cu vai giay trong khi giao dien da sang chuong moi.
        playToken++
        val loadToken = playToken
        restartOnResume = false
        // Nguong cau cua che do ngu "het cau" thuoc ve chuong cu; nap chuong moi
        // thi huy, neu khong no ro sang chuong moi va khong bao gio kich hoat.
        val disarmSentenceSleep = stopAfterSentence
        stopAfterSentence = false
        stopAfterSentenceAt = -1
        releasePlayer()
        chapterSig = ""
        chunkDurations.clear()
        offsetsDirty = true
        _ui.update {
            val keepToc = baseStory == null
            it.copy(
                chapterNumber = requestedNumber,
                chapterTitle = knownTitle,
                sentences = emptyList(),
                activeSentenceIndex = 0,
                progress = 0f,
                positionMs = 0L,
                durationMs = 0L,
                tocUrls = if (keepToc) it.tocUrls else emptyList(),
                tocTitles = if (keepToc) it.tocTitles else emptyList(),
                chapterItems = if (keepToc) it.chapterItems else emptyList(),
                isBuffering = true,
                isPlaying = false,
                prefetchStatus = null,
                ttsFallbackReason = null,
                errorMessage = null,
                chapterLoadError = null,
                sleepMode = if (disarmSentenceSleep) SleepMode.OFF else it.sleepMode,
            )
        }
        publishPlayback()
        chapterLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureReady()
                if (epubRef == null && url.startsWith("epub://")) throw ApiError(400, "Đường dẫn chương EPUB không hợp lệ")
                val warmAtLoad = warmedChapters[url]
                val responseJson = browserChapter ?: warmAtLoad?.responseJson ?: if (epubRef != null) {
                    ReadingEngine.epubChapter(epubRef.first, epubRef.second)
                } else {
                    ReadingEngine.chapter(url)
                }
                val res = JSONObject(responseJson)
                if (!isActive) return@launch
                val content = res.optString("content", "")
                val chTitle = res.optString("title", "").ifBlank { knownTitle }
                val storyTitle = GenericExtractor.cleanStoryTitle(res.optString("story_title"), chTitle)
                    .ifBlank { GenericExtractor.cleanStoryTitle(requestedStory?.title.orEmpty(), chTitle) }
                val nextUrl = res.chapterLink("next_url")?.takeUnless { it == url }
                val prevUrl = res.chapterLink("prev_url")?.takeUnless { it == url }
                val chapterUrl = res.optString("chapter_url", url)
                val coverUrl = res.optString("cover_url", "")
                val chunks = splitIntoChunks(content)
                if (chunks.isEmpty()) throw ApiError(422, "Chương không có nội dung để đọc")

                val base=requestedStory;val isEpub=epubRef!=null||base?.sourceType==StorySourceType.EPUB
                val stableStoryUrl=if(isEpub)null else base?.storyUrl?:StoryIds.guessStoryUrlFromChapter(chapterUrl).ifBlank{null}
                val sid=if(isEpub)(base?.id?:epubRef?.first?:"epub")else StoryIds.storyId(base?.id,stableStoryUrl,chapterUrl,title=storyTitle.ifBlank{base?.title},source=base?.source)
                val chapterId = StoryIds.chapterId(chapterUrl)
                // Fix G: so chuong cua nguon online truoc day bi ghim cung o
                // `base?.currentChapter ?: 1` nen KHONG BAO GIO doi khi next/prev.
                // Hau qua: moi ban ghi chapter_history mang cung mot chapterIndex,
                // danh sach chuong khong sap xep duoc, va lui chuong nhan sai vi tri.
                val resolvedChapterNo = resolveChapterNumber(
                    isEpub = isEpub,
                    epubIndex = res.optInt("chapter_index", -1),
                    storyId = sid,
                    chapterUrl = chapterUrl,
                    requestUrl = url,
                    hint = chapterNumberHint,
                    delta = chapterDelta,
                    base = base?.currentChapter,
                )
                val lastCount = base?.sentenceCount ?: 0
                val startIdx = if (resumeSentence > 0 && lastCount > 0 && lastCount != chunks.size) {
                    ((resumeSentence.toLong() * chunks.size) / lastCount).toInt().coerceIn(0, chunks.lastIndex)
                } else resumeSentence.coerceIn(0, chunks.lastIndex)
                val story = (base ?: Story(
                    id = sid, title = storyTitle.ifBlank { "Truyện" }, author = "",
                    coverUrl = coverUrl, totalChapters = 0, tags = emptyList(), color = DEFAULT_ACCENT
                )).copy(
                    id = sid,
                    title = if (isEpub && base != null) base.title else storyTitle.ifBlank { "Truyện" },
                    coverUrl = if (coverUrl.isNotBlank()) coverUrl else (base?.coverUrl ?: ""),
                    storyUrl=stableStoryUrl,sourceType=if(isEpub)StorySourceType.EPUB else(base?.sourceType?:StorySourceType.ONLINE),localBookId=if(isEpub)(base?.localBookId?:epubRef?.first)else base?.localBookId,source=if(isEpub)"EPUB ngoại tuyến" else base?.source,totalChapters=res.optInt("total_chapters",base?.totalChapters?:0),currentChapter=resolvedChapterNo,
                    chapterUrl = chapterUrl,
                    nextUrl = nextUrl,
                    prevUrl = prevUrl,
                    chapterTitle = chTitle,
                    sentenceIndex = startIdx,
                    sentenceCount = chunks.size,
                )

                val profile = if (s.voiceProfileEnabled) smartStore.profile(story.id) else null
                val profileVoice = profile?.first?.let { stored ->
                    OfflineVoicePolicy.migrate(stored)
                }
                val effectiveVoice = profileVoice ?: s.selectedVoice
                val effectiveSpeed = profile?.second?.coerceIn(0.5f,3f) ?: s.speed
                val estTotal = chunks.sumOf { estimateDurationMs(it, effectiveSpeed) }
                withContext(Dispatchers.Main) {
                if (loadToken != playToken) return@withContext
                chapterSig = chapterUrl
                if (browserChapter != null) {
                    warmAudioTasks.remove(warmAudioKey(chapterUrl, effectiveVoice))?.cancel()
                }
                // Fix D: KHONG clear audioCache nua. synthKey() da chua chapterSig nen
                // cac chuong khong dam nhau, va LruByteCache tu gioi han theo dung luong.
                // Nho vay lui chuong / quay lai chuong vua nghe khong phai tong hop lai
                // tu dau (ttsCache cua ReadingEngine chi cuu duoc phan mang, khong cuu duoc
                // phan giai ma MediaCodec).
                chunkDurations.clear()
                offsetsDirty = true
                // Seed am thanh doan dau da duoc warmChapter() chuan bi san.
                run {
                    val warm = warmedChapters.remove(chapterUrl)
                        ?: warmedChapters.remove(url)
                        ?: warmAtLoad
                    warmedChapters.remove(url)
                    val warmBytes = warm?.bytes
                    if (browserChapter == null && warm != null && warmBytes != null &&
                        warm.voice == effectiveVoice && startIdx == 0 &&
                        chunks.isNotEmpty() && chunks[0] == warm.firstChunkText) {
                        val key = synthKeyFor(chapterUrl, 0, effectiveVoice, chunks[0])
                        audioCache.put(key, warmBytes)
                        warm.decoded?.let { pcmCache.put(key, it) }
                        warmAudioTasks.remove(warmAudioKey(url, effectiveVoice))
                        warmAudioTasks.remove(warmAudioKey(chapterUrl, effectiveVoice))
                    }
                }

                _ui.update {
                    it.copy(
                        activeStory = story,
                        chapterNumber = resolvedChapterNo,
                        chapterTitle = chTitle,
                        sentences = chunks,
                        activeSentenceIndex = startIdx,
                        progress = if (chunks.isEmpty()) 0f else startIdx.toFloat() / chunks.size,
                        positionMs = 0L,
                        durationMs = estTotal,
                        isBuffering = false,
                        isPlaying = false,
                        selectedVoice = effectiveVoice,
                        speed = effectiveSpeed,
                    )
                }
                // Fix B: dung dung loadToken.
                // Truoc day truyen playToken roi speakFrom() lai ++playToken mot lan
                // nua ngay sau do, nen ca 4 job prefetch nay chet o dong
                // `if (token != playToken) return@launch` truoc khi kip chay - dung
                // tren nhanh autoPlay, tuc dung luc chuyen chuong.
                // CapCut giờ prefetch song song (provider có 3 lane + throttle toàn cục);
                // stagger ngắn để câu đầu chương giành lane trước, hết delay hàng đợi.
                prefetchWindow(
                    startIdx, loadToken,
                    count = PlaybackPrefetchPolicy.parallelPrefetchCount(effectiveVoice),
                    staggerMs = PlaybackPrefetchPolicy.PREFETCH_STAGGER_MS,
                )
                publishPlayback()
                saveProgressAndHistory(story, chapterId, chTitle, startIdx, chunks.size, completed = false)
                loadToc(story)
                warmNeighborChapters()
                if (autoPlay && chunks.isNotEmpty()) speakFrom(startIdx, loadToken)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!isActive || loadToken != playToken) return@launch
                val failedStory = requestedStory
                val accessCheckRequired = e.message
                    ?.contains("yêu cầu xác minh", ignoreCase = true) == true || (e as? ApiError)?.status == 429
                val targetChapter = chapterNumberHint ?: requestedNumber
                val backup = if (browserChapter == null && allowSourceFallback && !accessCheckRequired &&
                    failedStory?.sourceType == StorySourceType.ONLINE
                )
                    findBackupChapter(failedStory, targetChapter) else null
                if (backup != null) {
                    val (mirrorStoryUrl, mirrorChapterUrl) = backup
                    val switched = failedStory!!.copy(
                        storyUrl = mirrorStoryUrl,
                        source = StoryIds.sourceLabel(mirrorStoryUrl),
                        mirrorUrls = (failedStory.mirrorUrls - mirrorStoryUrl) + listOfNotNull(failedStory.storyUrl),
                    )
                    withContext(Dispatchers.Main) {
                        if (loadToken != playToken) return@withContext
                        chapterLoadJob = null
                        loadChapter(mirrorChapterUrl, autoPlay=autoPlay, baseStory=switched,
                            resumeSentence=resumeSentence, chapterNumberHint=targetChapter,
                            allowSourceFallback=false)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (loadToken != playToken) return@withContext
                    retryChapterLoad = {
                        loadChapter(url, autoPlay, baseStory, resumeSentence, chapterDelta, chapterNumberHint, allowSourceFallback, browserChapter)
                    }
                    val detail = e.message?.takeIf(String::isNotBlank) ?: "Không tải được chương"
                    _ui.update {
                        it.copy(isBuffering = false, isPlaying = false, chapterLoadError = detail)
                    }
                    publishPlayback()
                }
            }
        }
    }

    private fun JSONObject.chapterLink(key: String): String? =
        if (isNull(key)) null else optString(key).takeUnless { it.isBlank() || it == "null" }

    private fun parseEpubRef(url: String): Pair<String, Int>? {
        if (!url.startsWith("epub://")) return null
        val parts = url.removePrefix("epub://").split('/')
        val id = parts.firstOrNull()?.takeIf { value: String -> value.isNotBlank() } ?: return null
        if (parts.size != 2) return null
        val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return id to index
    }

    /**
     * Fix G: doc so chuong tu URL. Gan nhu moi nguon tieng Viet deu nhung so
     * chuong vao duong dan ("/chuong-123", "/chapter_45", "/c67.html"). Day la
     * nguon dang tin cay nhat va con tu chua duoc du lieu cu bi ghi sai so.
     */
    private fun chapterNumberFromUrl(rawUrl: String?): Int? {
        val clean = rawUrl?.substringBefore('#')?.substringBefore('?')?.trimEnd('/') ?: return null
        val tail = clean.substringAfterLast('/').lowercase()
        if (tail.isBlank()) return null
        val keyed = Regex("(?:chuong|chapter|chap|ch|c)[-_.]?(\\d{1,6})").find(tail)?.groupValues?.getOrNull(1)
        val digits = keyed ?: Regex("(\\d{1,6})(?!.*\\d)").find(tail)?.groupValues?.getOrNull(1)
        return digits?.toIntOrNull()?.takeIf { value -> value in 1..999_999 }
    }

    /** So chuong da tung luu cho dung URL nay. */
    private fun storedChapterNumber(storyId: String, chapterUrl: String?): Int? {
        val target = StoryIds.normalizeUrl(chapterUrl)
        if (target.isBlank()) return null
        return try {
            Store.getChapterHistory(storyId).firstOrNull { item ->
                StoryIds.normalizeUrl((item["chapterUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()) == target
            }?.let { item -> (item["chapterIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
                ?.takeIf { value -> value > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveChapterNumber(
        isEpub: Boolean,
        epubIndex: Int,
        storyId: String,
        chapterUrl: String,
        requestUrl: String,
        hint: Int?,
        delta: Int,
        base: Int?,
    ): Int {
        if (isEpub) return (if (epubIndex >= 0) epubIndex + 1 else (base ?: 1)).coerceAtLeast(1)
        chapterNumberFromUrl(chapterUrl)?.let { value -> return value }
        chapterNumberFromUrl(requestUrl)?.let { value -> return value }
        hint?.takeIf { value -> value > 0 }?.let { value -> return value }
        storedChapterNumber(storyId, chapterUrl)?.let { value -> return value }
        if (delta != 0) return ((base ?: 1) + delta).coerceAtLeast(1)
        return (base ?: 1).coerceAtLeast(1)
    }

    /**
     * Fix G: danh sach chuong cho nguon online (link dan).
     * Truoc day ham nay GHI DE danh sach bang DUY NHAT chuong dang doc, nen:
     *  - nguoi dung khong bao gio thay lai cac chuong da mo,
     *  - setChapter() khong co gi de tra cuu,
     *  - lui chuong mat diem tua khi nguon khong tra prev_url.
     * Nay danh sach duoc dung lai tu chapter_history da luu, gop them chuong
     * hien tai, roi sap xep theo SO CHUONG.
     */
    /**
     * Fix G: danh sach chuong cho nguon online (link dan).
     * Truoc day ham nay GHI DE danh sach bang DUY NHAT chuong dang doc, nen:
     *  - nguoi dung khong bao gio thay lai cac chuong da mo,
     *  - setChapter() khong co gi de tra cuu,
     *  - lui chuong mat diem tua khi nguon khong tra prev_url.
     * Nay danh sach duoc dung lai tu chapter_history da luu, gop them chuong
     * hien tai, roi sap xep theo SO CHUONG.
     */
    private fun loadCurrentChapterOnly(story: Story) {
        val url = story.chapterUrl ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = try { Store.getChapterHistory(story.id) } catch (_: Exception) { emptyList() }

            fun field(obj: kotlinx.serialization.json.JsonObject?, key: String): String =
                (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

            fun statusOf(raw: String, fallback: ChapterListenStatus): ChapterListenStatus =
                try {
                    ChapterListenStatus.valueOf(raw.ifBlank { fallback.name })
                } catch (_: IllegalArgumentException) {
                    fallback
                }

            val currentKey = StoryIds.normalizeUrl(url)
            val merged = LinkedHashMap<String, ChapterUiModel>()

            saved.forEach { item ->
                val itemUrl = field(item, "chapterUrl")
                val key = StoryIds.normalizeUrl(itemUrl)
                if (key.isBlank()) return@forEach
                val number = chapterNumberFromUrl(itemUrl)
                    ?: field(item, "chapterIndex").toIntOrNull()?.takeIf { it > 0 }
                    ?: (merged.size + 1)
                merged[key] = ChapterUiModel(
                    index = number,
                    title = field(item, "chapterTitle").ifBlank { chapTitle(number) },
                    url = itemUrl,
                    chapterId = field(item, "chapterId").ifBlank { StoryIds.chapterId(itemUrl, number) },
                    status = statusOf(field(item, "status"), ChapterListenStatus.UNPLAYED),
                    progress = field(item, "progress").toFloatOrNull() ?: 0f,
                    isCurrent = key == currentKey,
                    lastListenedAt = field(item, "lastListenedAt").toLongOrNull() ?: 0L,
                )
            }

            val history = saved.firstOrNull { item ->
                StoryIds.normalizeUrl(field(item, "chapterUrl")) == currentKey
            }
            val chapterIndex = story.currentChapter.coerceAtLeast(1)
            merged[currentKey] = ChapterUiModel(
                index = chapterIndex,
                title = story.chapterTitle.ifBlank { s.chapterTitle.ifBlank { chapTitle(chapterIndex) } },
                url = url,
                chapterId = StoryIds.chapterId(url, chapterIndex),
                status = statusOf(field(history, "status"), ChapterListenStatus.IN_PROGRESS),
                progress = field(history, "progress").toFloatOrNull() ?: s.progress,
                isCurrent = true,
                lastListenedAt = System.currentTimeMillis(),
            )

            val items = merged.values.sortedWith(
                compareBy<ChapterUiModel> { item -> item.index }.thenBy { item -> item.title }
            )
            val maxIndex = items.maxOfOrNull { item -> item.index } ?: chapterIndex

            _ui.update { cur ->
                if (cur.activeStory?.id != story.id ||
                    StoryIds.normalizeUrl(cur.activeStory.chapterUrl) != StoryIds.normalizeUrl(story.chapterUrl)) return@update cur
                cur.copy(
                    tocUrls = items.map { item -> item.url },
                    tocTitles = items.map { item -> item.title },
                    chapterItems = items,
                    chapterNumber = chapterIndex,
                    activeStory = cur.activeStory?.let { st ->
                        st.copy(totalChapters = maxOf(st.totalChapters, maxIndex))
                    },
                )
            }
            publishPlayback()
        }
    }

    private fun loadToc(story: Story) {
        val epubId: String? = story.localBookId
            ?: parseEpubRef(story.chapterUrl.orEmpty())?.first
        val isEpub = story.sourceType == StorySourceType.EPUB || epubId != null
        // Pasted-link / online stories: don't fetch the full chapter list — show only the current chapter.
        if (!isEpub) {
            loadCurrentChapterOnly(story)
            return
        }
        if (s.tocUrls.size == story.totalChapters && s.tocUrls.isNotEmpty()) {
            _ui.update { current ->
                current.copy(chapterItems = current.chapterItems.map { chapter ->
                    val selected = chapter.index == story.currentChapter
                    if (chapter.isCurrent == selected) chapter else chapter.copy(isCurrent = selected)
                })
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response: JSONObject = JSONObject(ReadingEngine.epubInfo(epubId))

                val array = response.optJSONArray("chapters") ?: return@launch
                val urls = ArrayList<String>(array.length())
                val titles = ArrayList<String>(array.length())

                for (index in 0 until array.length()) {
                    val chapter = array.getJSONObject(index)
                    val chapterUrl = chapter.optString("url").ifBlank {
                        if (epubId != null) "epub://$epubId/$index" else ""
                    }
                    urls.add(chapterUrl)
                    titles.add(chapter.optString("title"))
                }

                val historyByUrl: Map<String, kotlinx.serialization.json.JsonObject> =
                    Store.getChapterHistory(story.id).associateBy { item: kotlinx.serialization.json.JsonObject ->
                        val rawUrl = (item["chapterUrl"] as? JsonPrimitive)
                            ?.contentOrNull
                            .orEmpty()
                        StoryIds.normalizeUrl(rawUrl)
                    }

                val currentNormalized = StoryIds.normalizeUrl(story.chapterUrl)
                val currentIndex = urls.indexOfFirst { chapterUrl: String ->
                    StoryIds.normalizeUrl(chapterUrl) == currentNormalized
                }

                val chapterItems: List<ChapterUiModel> = urls.mapIndexed { index: Int, chapterUrl: String ->
                    val history = historyByUrl[StoryIds.normalizeUrl(chapterUrl)]

                    fun historyString(key: String): String =
                        (history?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

                    val rawStatus = historyString("status").ifBlank {
                        ChapterListenStatus.UNPLAYED.name
                    }
                    val status = try {
                        ChapterListenStatus.valueOf(rawStatus)
                    } catch (_: IllegalArgumentException) {
                        ChapterListenStatus.UNPLAYED
                    }

                    ChapterUiModel(
                        index = index + 1,
                        title = titles.getOrElse(index) { chapTitle(index + 1) },
                        url = chapterUrl,
                        chapterId = if (epubId != null) {
                            "epub:$epubId:$index"
                        } else {
                            StoryIds.chapterId(chapterUrl, index + 1)
                        },
                        status = status,
                        progress = historyString("progress").toFloatOrNull() ?: 0f,
                        isCurrent = index == currentIndex,
                        lastListenedAt = historyString("lastListenedAt").toLongOrNull() ?: 0L,
                    )
                }

                _ui.update { currentState: UiState ->
                    val activeStory = currentState.activeStory
                    if (activeStory == null || activeStory.id != story.id ||
                        activeStory.chapterUrl != story.chapterUrl || currentState.sentences.isEmpty()
                    ) return@update currentState
                    val chapterNumber = if (currentIndex >= 0) {
                        currentIndex + 1
                    } else {
                        story.currentChapter
                    }
                    currentState.copy(
                        tocUrls = urls,
                        tocTitles = titles,
                        chapterItems = chapterItems,
                        chapterNumber = chapterNumber,
                        activeStory = activeStory.copy(
                            totalChapters = urls.size,
                            currentChapter = chapterNumber,
                        ),
                    )
                }
                publishPlayback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update { currentState ->
                    val activeStory = currentState.activeStory
                    if (activeStory?.id == story.id && activeStory.chapterUrl == story.chapterUrl &&
                        currentState.sentences.isNotEmpty()
                    ) currentState.copy(errorMessage = error.message ?: "Không tải được danh sách chương")
                    else currentState
                }
            }
        }
    }

    private fun adjacentChapterUrl(direction: Int): String? {
        val story = s.activeStory ?: return null
        val direct = if (direction > 0) story.nextUrl else story.prevUrl
        return direct?.takeIf { it.isNotBlank() && it != "null" && it != story.chapterUrl }
            ?: s.chapterItems.firstOrNull { it.index == story.currentChapter + direction }?.url
    }

    fun nextChapter() {
        // Ignore repeated taps while a network chapter is loading. Never queue
        // rapid requests that can produce stale URLs or overwhelm a source.
        if (chapterLoadJob?.isActive == true) return
        // Fix G: nguon co the khong tra next_url. Dung danh sach chuong da luu
        // lam diem tua thay vi chet cung o day.
        val n = adjacentChapterUrl(1)
        if (n.isNullOrBlank()) return
        // Fix F: bo qua giua chuong khong con bi danh dau la da nghe xong.
        // markCurrentChapter tu xet nguong 0.95 khi completed = false.
        markCurrentChapter(completed = false)
        loadChapter(n, autoPlay = s.isPlaying, chapterDelta = 1)
    }

    fun prevChapter() {
        if (chapterLoadJob?.isActive == true) return
        // Fix G: rat nhieu nguon bo trong prev_url, khien nut Lui chuong khong
        // lam gi ca. Tra cuu nguoc trong danh sach chuong da mo theo SO CHUONG.
        val p = adjacentChapterUrl(-1)
        if (p.isNullOrBlank()) return
        markCurrentChapter(completed = false)
        loadChapter(p, autoPlay = s.isPlaying, chapterDelta = -1)
    }

    fun setChapter(n: Int) {
        if (chapterLoadJob?.isActive == true) return
        _ui.update { it.copy(sheet = null) }
        // Fix G: danh sach chuong online la danh sach THUA (chi gom cac chuong da
        // mo), nen tra cuu bang tocUrls[n - 1] se nhay sai chuong. Tim dung so chuong.
        // Khong fallback theo vi tri tocUrls[n - 1]: danh sach online THUA
        // (chi gom chuong da mo) nen chi so vi tri se nap nham chuong khac.
        val url = s.chapterItems.firstOrNull { item -> item.index == n }?.url
        when {
            url != null -> loadChapter(url, autoPlay = s.isPlaying, chapterNumberHint = n)
            n == s.activeStory?.currentChapter?.plus(1) -> nextChapter()
            n == s.activeStory?.currentChapter?.minus(1) -> prevChapter()
        }
    }

    // ---------- playback ----------
    fun togglePlay() {
        if (s.sentences.isEmpty()) {
            if (s.chapterLoadError != null) retryChapterLoad?.invoke()
            return
        }
        if (s.isPlaying) pause() else resume()
    }

    fun togglePlayIfPaused() { if (s.sentences.isNotEmpty() && !s.isPlaying) resume() }
    fun pauseFromMedia() { pause() }

    /** Nut Dung tren thanh media: tat am + go notification, khong tu hien lai. */
    fun stopFromMedia() {
        // Bat flag TRUOC stopPlayback: stopPlayback goi publishPlayback(), collector
        // ReaderActivity nhin thay isPlaying=false nhung activeStory van con. Neu flag
        // chua bat, no se start lai ReadingPlaybackService ngay -> nut Dung vo nghia.
        mediaSessionDismissed = true
        stopPlayback()
        runCatching { ReadingPlaybackService.stop(getApplication()) }
    }

    /** Collector ReaderActivity hoi: con giu foreground media session khong. */
    fun wantsMediaSession(): Boolean =
        !mediaSessionDismissed && s.activeStory != null

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun resume() {
        mediaSessionDismissed = false
        requestAudioFocus()
        if (restartOnResume) {
            restartOnResume = false
            speakFrom(s.activeSentenceIndex)
            return
        }
        // Dang nghen (isBuffering) nhung feeder con song va engine con audio
        // thi cu play() tiep: speakFrom() o day se reset engine va tong hop
        // lai cac doan da co san trong hang doi.
        if (pcmActive && pcmEngine.hasAudio() && (!s.isBuffering || feedJob?.isActive == true)) {
            pcmEngine.play()
            _ui.update { it.copy(isPlaying = true) }
            publishPlayback()
        } else {
            speakFrom(s.activeSentenceIndex)
        }
    }

    private fun pause() {
        if (pcmActive) pcmEngine.pause()
        _ui.update { it.copy(isPlaying = false) }
        publishPlayback()
        persistProgress()
    }

    private fun stopPlayback() {
        voiceSwitchJob?.cancel()
        voiceSwitchJob = null
        volumeRampJob?.cancel()
        volumeRampJob = null
        voiceFadeInPending = false
        playToken++
        releasePlayer()
        abandonAudioFocus()
        _ui.update { it.copy(isPlaying = false, isBuffering = false) }
        publishPlayback()
    }

    private fun releasePlayer() {
        feedJob?.cancel()
        feedJob = null
        if (pcmActive) pcmEngine.reset()
    }

    private fun progressFor(index: Int): Float {
        val size = s.sentences.size
        return if (size <= 0) 0f else (index.toFloat() / size).coerceIn(0f, 1f)
    }

    /** Stable fraction based on sentence order instead of mutable duration sums. */
    private fun stableProgressFor(index: Int, withinChunkMs: Long = 0L): Float {
        val size = s.sentences.size
        if (size <= 0) return 0f
        val i = index.coerceIn(0, size - 1)
        val duration = (chunkDurations[i]
            ?: estimateDurationMs(s.sentences[i], s.speed)).coerceAtLeast(1L)
        val inside = (withinChunkMs.toFloat() / duration.toFloat()).coerceIn(0f, 0.999f)
        return ((i + inside) / size.toFloat()).coerceIn(0f, 1f)
    }

    /** Called after seek, replay, voice/speed restart, or chapter change. */
    private fun resetProgressClock(index: Int) {
        progressClockToken = playToken
        progressFloorMs = positionMsFor(index)
        progressFloorFraction = progressFor(index)
        lastTickPosSec = -1L
        lastTickFracBucket = -1
        _progressUi.value = PlaybackProgressState(
            positionMs = progressFloorMs,
            durationMs = durationMsTotal(),
            progress = progressFloorFraction,
        )
    }

    /**
     * Rebuild the prefix-sum of chunk start offsets. O(n), but runs only when a
     * chunk's real duration is learned or sentences/speed change — NOT every tick.
     */
    private fun recomputeOffsetsIfNeeded() {
        if (!offsetsDirty) return
        val sentences = s.sentences
        val speed = s.speed
        val arr = LongArray(sentences.size + 1)
        var acc = 0L
        for (i in sentences.indices) {
            acc += chunkDurations[i] ?: estimateDurationMs(sentences[i], speed)
            arr[i + 1] = acc
        }
        chunkOffsets = arr
        offsetsDirty = false
    }

    private fun positionMsFor(index: Int, withinChunkMs: Long = 0L): Long {
        recomputeOffsetsIfNeeded()
        val i = index.coerceIn(0, chunkOffsets.size - 1)
        return chunkOffsets[i] + withinChunkMs
    }

    private fun durationMsTotal(): Long {
        if (s.sentences.isEmpty()) return 0L
        recomputeOffsetsIfNeeded()
        return chunkOffsets[chunkOffsets.size - 1]
    }

    // Tốc độ được AudioTrack áp dụng trực tiếp, không phải thuộc tính của MP3/PCM.
    // Bỏ speed khỏi key để kéo slider không làm mất toàn bộ cache audio.
    private fun synthKeyFor(chapter: String, index: Int, voice: String, text: String) =
        "$voice|$chapter|$index|$text"

    private fun synthKey(index: Int) =
        synthKeyFor(chapterSig, index, s.selectedVoice, s.sentences.getOrNull(index).orEmpty())

    private fun warmAudioKey(chapter: String, voice: String): String = "$voice|$chapter"

    private suspend fun synth(index: Int): ByteArray? =
        synthesizeForVoice(index, s.selectedVoice, updateUi = true)

    private suspend fun synthesizeForVoice(
        index: Int,
        voice: String,
        updateUi: Boolean,
    ): ByteArray? {
        val epoch = warmEpoch.get()
        val chapter = chapterSig
        val rawText = s.sentences.getOrNull(index) ?: return null
        val text = smartStore.applyPronunciation(rawText, s.activeStory?.id)
        if (text.isBlank()) return null
        val key = synthKeyFor(chapter, index, voice, rawText)
        val provider = voice.substringBefore(':').replaceFirstChar { it.uppercase() }
        val token = playToken
        fun reportFailure(error: Exception, started: Long) {
            if (!updateUi || chapter != chapterSig || token != playToken) return
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "$provider không phản hồi"
            val latency = android.os.SystemClock.elapsedRealtime() - started
            _ui.update { state ->
                state.copy(ttsProvider = provider, ttsLatencyMs = latency, ttsFallbackReason = detail, errorMessage = detail)
            }
        }
        audioCache.get(key)?.let {
            if (updateUi) _ui.update { state ->
                state.copy(ttsProvider = provider, ttsLatencyMs = 0L, ttsFallbackReason = null)
            }
            return it
        }
        // Nếu warm câu đầu vẫn đang chạy khi chương đổi, dùng chung đúng task đó
        // thay vì gửi thêm một request CapCut trùng lặp rồi chờ hai lần.
        if (index == 0 && voice == s.selectedVoice) {
            val warmTask = warmAudioTasks[warmAudioKey(chapter, voice)]
            if (warmTask != null) {
                val warmed = try {
                    warmTask.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (warmed != null) {
                    synchronized(warmEpoch) {
                        if (epoch != warmEpoch.get()) throw CancellationException("Cache đã bị xoá")
                        audioCache.put(key, warmed.bytes)
                        warmed.decoded?.let { pcmCache.put(key, it) }
                    }
                    if (updateUi) _ui.update { state -> state.copy(
                            ttsProvider = provider,
                            ttsLatencyMs = 0L,
                            ttsFallbackReason = null,
                        )
                    }
                    return warmed.bytes
                }
            }
        }

        val started = android.os.SystemClock.elapsedRealtime()
        val mine = CompletableDeferred<ByteArray?>()
        val existing = synthInFlight.putIfAbsent(key, mine)
        if (existing != null) {
            // Chu so huu bi huy -> deferred loi bang SharedSynthCancelled (khong
            // phai CancellationException) nen ben cho chi mat doan nay, khong chet.
            return try {
                existing.await()
            } catch (_: SharedSynthCancelled) {
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error, started)
                null
            }
        }

        try {
            val body = JSONObject()
                .put("text", text)
                .put("voice_id", voice)
                .put("speed", 1.0)
                .put("pitch", 1.0)
                .put("normalize", false)
            val bytes = ReadingEngine.synthesize(body.toString())
            synchronized(warmEpoch) {
                if (epoch != warmEpoch.get()) throw CancellationException("Cache đã bị xoá")
                audioCache.put(key, bytes)
            }
            mine.complete(bytes)
            val latency = android.os.SystemClock.elapsedRealtime() - started
            if (updateUi) _ui.update { state ->
                state.copy(ttsProvider = provider, ttsLatencyMs = latency, ttsFallbackReason = null)
            }
            return bytes
        } catch (cancelled: CancellationException) {
            mine.completeExceptionally(SharedSynthCancelled(cancelled))
            throw cancelled
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            reportFailure(e, started)
            return null
        } finally {
            synchronized(synthInFlight) {
                if (synthInFlight[key] === mine) synthInFlight.remove(key)
            }
        }
    }

    private fun prefetch(index: Int, token: Int, staggerMs: Long = 0L) {
        if (index < 0 || index >= s.sentences.size) return
        viewModelScope.launch(Dispatchers.IO) {
            // Stagger ngắn để câu đang phát (feeder gọi synth trực tiếp) giành
            // lane CapCut trước, prefetch không tạo backlog chắn đầu câu.
            if (staggerMs > 0L) delay(staggerMs)
            if (token != playToken) return@launch
            synth(index)
        }
    }

    private fun prefetchWindow(
        startIndex: Int,
        token: Int,
        count: Int = 3,
        staggerMs: Long = 0L,
    ) {
        if (count <= 0) return
        val start = startIndex.coerceAtLeast(0)
        if (start >= s.sentences.size) return
        val end = minOf(s.sentences.lastIndex, start + count - 1)
        for (i in start..end) prefetch(i, token, staggerMs * (i - start + 1))
    }

    /**
     * Bat dau mot phien phat tai [index].
     *
     * [reuseToken] cho phep loadChapter() giao lai chinh token no vua cap thay vi
     * de speakFrom cap token moi - neu khong, moi prefetch ma loadChapter da khoi
     * dong deu bi vo hieu ngay tai khe chuyen chuong (Fix B).
     */
    private fun speakFrom(index: Int, reuseToken: Int? = null) {
        mediaSessionDismissed = false
        requestAudioFocus()
        pcmSpeakFrom(index, reuseToken)
    }

    // ---------- Single gapless PCM playback path ----------


    /**
     * Bat dau phien phat PCM tai [index].
     *
     * Duong PCM khong con khai niem "chuan bi player cho doan
     * ke tiep" va "ban giao". Chi co MOT AudioTrack chay lien tuc va mot feeder
     * nap PCM vao no, nen chuyen doan khong con la mot su kien co the tre.
     */
    private fun pcmSpeakFrom(index: Int, reuseToken: Int? = null) {
        restartOnResume = false
        pcmActive = true
        val token = reuseToken ?: ++playToken
        // Huy feeder cu va reset AudioTrack truoc khi bat dau phien moi.
        releasePlayer()
        if (index < 0 || index >= s.sentences.size) { onChapterEnd(); return }
        resetProgressClock(index)
        pcmEngine.setSpeed(s.speed.coerceIn(0.5f, 3.0f))
        pcmEngine.setVolume(if (voiceFadeInPending) 0f else s.playerVolume)
        _ui.update {
            it.copy(
                isPlaying = true,
                isBuffering = true,
                activeSentenceIndex = index,
                progress = progressFor(index),
                positionMs = positionMsFor(index),
            )
        }
        publishPlayback()
        pcmEngine.play()
        startPcmFeeder(index, token)
    }

    /**
     * Nap PCM cho engine theo thu tu, giu hang doi luon sau [pcmQueueDepth] doan.
     *
     * Feeder PCM giu san mot cua so cac doan tiep theo:
     * feeder chay lien tuc va tu chan lai khi hang doi day, nen do sau duong ong
     * KHONG BAO GIO tut ve 1 nhu loi cu.
     */
    private fun startPcmFeeder(startIndex: Int, token: Int) {
        feedJob?.cancel()
        feedJob = viewModelScope.launch(Dispatchers.IO) {
            var i = startIndex
            var failures = 0
            val isCapcut = s.selectedVoice.startsWith("capcut:", ignoreCase = true)
            var nextWarmStarted = false
            while (isActive && token == playToken && i < s.sentences.size) {
                if (pcmEngine.queued() >= pcmQueueDepth) {
                    val nextUrl = adjacentChapterUrl(1)
                    if (PlaybackPrefetchPolicy.shouldWarmSerializedNext(
                            voiceId = s.selectedVoice,
                            queued = pcmEngine.queued(),
                            queueDepth = pcmQueueDepth,
                            hasNext = !nextUrl.isNullOrBlank(),
                            alreadyStarted = nextWarmStarted,
                        )) {
                        nextWarmStarted = warmCapcutNextAudio(nextUrl, token)
                    }
                    // Queue da du am thanh; ngu lau hon de nhường CPU cho UI/
                    // RenderThread ma van du xa nguong underrun.
                    delay(60)
                    continue
                }
                // CapCut giờ chạy song song 3 lane trong provider; prefetch có
                // stagger nên không tranh lane với câu đang phát (khắc phục đúng
                // lỗi backlog của thiết kế tuần tự trước đây).
                prefetchWindow(i + 1, token, staggerMs = PlaybackPrefetchPolicy.PREFETCH_STAGGER_MS)
                val decodeEpoch = warmEpoch.get()
                // CancellationException tu mot deferred dung chung (chu so huu
                // bi huy) khong duoc giet feeder: feeder con song thi coi nhu
                // doan nay that bai va di qua nhanh thu lai ben duoi.
                val bytes = try {
                    synth(i)
                } catch (cancelled: CancellationException) {
                    if (isActive && token == playToken) null else throw cancelled
                }
                if (!isActive || token != playToken) return@launch
                val decodeKey = synthKey(i)
                val decoded = pcmCache.get(decodeKey) ?: if (bytes == null) {
                    null
                } else {
                    PcmDecoder.decode(
                        bytes,
                        tailSilenceMs = SpeechAudioPolicy.tailSilenceMs(
                            s.sentences.getOrNull(i).orEmpty()
                        ),
                    )?.also { pcm -> synchronized(warmEpoch) {
                        if (decodeEpoch == warmEpoch.get() && token == playToken) pcmCache.put(decodeKey, pcm)
                    } }
                }
                if (!isActive || token != playToken) return@launch
                val truncated = decoded != null && isCapcut &&
                    CapcutTtsProtocol.isSuspiciouslyShort(
                        s.sentences.getOrNull(i)?.length ?: 0,
                        decoded.durationMs,
                    )
                if (decoded == null || truncated) {
                    failures++
                    // Không bỏ qua câu lỗi. Xóa file hỏng rồi thử lại chính câu đó;
                    // trước đây i++ khiến người nghe thấy mất câu hoặc ngắt hẳn.
                    audioCache.remove(synthKey(i))
                    pcmCache.remove(synthKey(i))
                    val why = if (truncated) {
                        "CapCut trả âm thanh ngắn bất thường; đang tạo lại câu hiện tại"
                    } else {
                        s.ttsFallbackReason ?: TtsManager.lastError
                    }
                    if (!why.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            _ui.update { st -> st.copy(ttsFallbackReason = why) }
                        }
                    }
                    val failureLimit = if (isCapcut && bytes == null) 1 else 3
                    if (failures >= failureLimit) {
                        withContext(Dispatchers.Main) { onPcmFatal() }
                        return@launch
                    }
                    delay(400L * failures)
                    continue
                }
                failures = 0
                val exact = (decoded.durationMs / s.speed.coerceIn(0.5f, 3.0f))
                    .toLong().coerceAtLeast(1L)
                val at = i
                withContext(Dispatchers.Main) {
                    // chunkDurations la HashMap -> chi ghi tren main thread.
                    // Thoi luong nay la SO THAT (tinh tu so frame PCM), khong con la
                    // uoc luong theo so ky tu, nen thanh tien trinh khop that.
                    chunkDurations[at] = exact
                    offsetsDirty = true
                }
                pcmEngine.submit(at, decoded)
                i++
            }
            // Fallback an toàn: nếu queue chưa từng đầy, khi toàn bộ câu hiện tại
            // đã submit thì provider đã rảnh. Chờ ngắn cho content-warm hoàn tất
            // rồi tạo trước câu đầu chương kế trong lúc các PCM cuối vẫn đang phát.
            if (isActive && token == playToken && isCapcut && !nextWarmStarted) {
                val nextUrl = adjacentChapterUrl(1)
                var attempts = 0
                while (!nextWarmStarted && attempts < 20 &&
                    isActive && token == playToken && !nextUrl.isNullOrBlank()
                ) {
                    nextWarmStarted = warmCapcutNextAudio(nextUrl, token)
                    if (!nextWarmStarted) delay(100L)
                    attempts++
                }
            }
        }
    }

    private fun onPcmChunkStarted(index: Int) {
        if (index < 0 || index >= s.sentences.size) return
        if (voiceFadeInPending) {
            voiceFadeInPending = false
            val targetVolume = s.playerVolume
            volumeRampJob?.cancel()
            volumeRampJob = viewModelScope.launch {
                rampPcmVolume(0f, targetVolume, 160L)
            }
        }
        if (stopAfterSentence && stopAfterSentenceAt >= 0 && index > stopAfterSentenceAt) {
            stopAfterSentence=false;stopAfterSentenceAt=-1;releasePlayer()
            _ui.update{it.copy(isPlaying=false,isBuffering=false,sleepMode=SleepMode.OFF)}
            publishPlayback();persistProgress();return
        }
        if (progressClockToken != playToken) resetProgressClock(index)
        progressFloorFraction = maxOf(progressFloorFraction, stableProgressFor(index))
        progressFloorMs = maxOf(progressFloorMs, positionMsFor(index))
        val liveDuration = maxOf(durationMsTotal(), progressFloorMs + 1L)
        _progressUi.value = PlaybackProgressState(
            progressFloorMs, liveDuration, progressFloorFraction,
        )
        _ui.update {
            it.copy(
                isBuffering = false,
                isPlaying = true,
                activeSentenceIndex = index,
                progress = progressFloorFraction,
                positionMs = progressFloorMs,
                durationMs = durationMsTotal(),
            )
        }
        publishPlayback()
        schedulePersist()
        maybeWarmNeighbors(index)
    }

    /** Engine da phat het buffer va hang doi rong. */
    private fun onPcmDrained(index: Int) {
        if (index >= s.sentences.size - 1) { onChapterEnd(); return }
        // Chua het chuong -> feeder khong theo kip (mang cham). Hien buffering roi
        // nap tiep, khong lam mat phien phat.
        _ui.update { it.copy(isBuffering = true) }
        publishPlayback()
        if (feedJob?.isActive != true) startPcmFeeder(index + 1, playToken)
    }

    private fun onPcmFatal() {
        volumeRampJob?.cancel()
        volumeRampJob = null
        voiceFadeInPending = false
        // Loi chi tu thi phai DUNG that: huy feeder (khong tong hop tiep vao
        // engine da hong) va pause+reset engine, neu khong tieng co the tu
        // song lai sau khi UI da bao dung.
        feedJob?.cancel()
        feedJob = null
        if (pcmActive) {
            pcmEngine.pause()
            pcmEngine.reset()
            pcmEngine.setVolume(s.playerVolume)
        }
        val detail = s.ttsFallbackReason
            ?: TtsManager.lastError?.takeIf { it.isNotBlank() }
            ?: "Không tạo được giọng đọc. Thử giọng Android (ngoại tuyến)."
        _ui.update {
            it.copy(
                isBuffering = false, isPlaying = false,
                errorMessage = detail,
                ttsFallbackReason = detail,
            )
        }
        publishPlayback()
    }

    /**
     * Fix C: quyet dinh khi nao warm hai chuong ke ben.
     *
     * Cu: chi warm o 2 doan CUOI chuong va chi khi bat autoNextChapter -> bam Next
     * giua chuong (truong hop pho bien nhat) khong he co gi duoc chuan bi truoc.
     * Moi: warm tu khoang 2/3 chuong, va warm chuong TRUOC ngay khi vao dau chuong
     * de nut Prev cung nhanh. Khong con phu thuoc autoNextChapter.
     */
    private fun maybeWarmNeighbors(index: Int) {
        val total = s.sentences.size
        if (total <= 0) return
        if (index <= 1 || index >= (total * 2) / 3) warmNeighborChapters()
    }

    private fun warmNeighborChapters() {
        warmChapter(adjacentChapterUrl(1))
        warmChapter(adjacentChapterUrl(-1))
    }

    /**
     * Nap truoc mot chuong o nen: do noi dung vao chapterCache cua ReadingEngine (bo
     * han cu fetch mang tai khe chuyen chuong) va tong hop san am thanh doan dau.
     * Ket qua duoc loadChapter tieu thu qua warmedChapters.
     */
    private fun warmChapter(rawUrl: String?) {
        val url = rawUrl?.takeIf { it.isNotBlank() } ?: return
        if (warmedChapters.containsKey(url)) return
        // Khac cu: co "dang warm" duoc GO RA trong finally, ke ca khi loi, nen mot
        // lan fetch that bai khong khoa vinh vien viec warm chuong nay.
        if (!warmingUrls.add(url)) return
        _ui.update { it.copy(prefetchStatus = "Đang tải trước chương kế tiếp") }
        val epoch = warmEpoch.get()
        val voice = s.selectedVoice
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val epubRef = parseEpubRef(url)
                val responseJson = if (epubRef != null) {
                    ReadingEngine.epubChapter(epubRef.first, epubRef.second)
                } else {
                    ReadingEngine.chapter(url)
                }
                val res = JSONObject(responseJson)
                val content = res.optString("content", "")
                val chapterUrl = res.optString("chapter_url", url)
                val firstText = splitIntoChunks(content, firstOnly = true).firstOrNull()
                val spokenFirstText = firstText?.let { smartStore.applyPronunciation(it, s.activeStory?.id) }
                // Không warm audio CapCut ở nền: task chương bên cạnh từng chen
                // trước task đang phát và làm hàng đợi hiện tại bị đói dữ liệu.
                val bytes = if (spokenFirstText != null &&
                    !voice.startsWith("capcut:", ignoreCase = true)
                ) {
                    try {
                        val body = JSONObject()
                            .put("text", spokenFirstText)
                            .put("voice_id", voice)
                            .put("speed", 1.0)
                            .put("pitch", 1.0)
                            .put("normalize", false)
                        ReadingEngine.synthesize(body.toString())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                } else null
                val decoded = bytes?.let {
                    PcmDecoder.decode(
                        it,
                        tailSilenceMs = SpeechAudioPolicy.tailSilenceMs(firstText.orEmpty()),
                    )
                }
                val warmed = WarmedChapter(
                    chapterUrl = chapterUrl,
                    firstChunkText = firstText ?: "",
                    voice = voice,
                    bytes = bytes,
                    decoded = decoded,
                    responseJson = responseJson,
                )
                // Luu theo ca URL yeu cau lan URL da giai quyet - hai cai co the khac
                // nhau (redirect / chuan hoa), loadChapter tra cuu duoc bang ca hai.
                synchronized(warmEpoch) {
                    if (epoch != warmEpoch.get()) return@launch
                    if (warmedChapters.size > 4) warmedChapters.clear()
                    warmedChapters[url] = warmed
                    warmedChapters[chapterUrl] = warmed
                }
                _ui.update { it.copy(prefetchStatus = if (bytes != null) "Chương kế tiếp và TTS đã sẵn sàng" else "Nội dung chương kế tiếp đã sẵn sàng") }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                if (epoch != warmEpoch.get()) return@launch
                _ui.update { it.copy(prefetchStatus = "Tải trước thất bại; sẽ tải khi chuyển chương") }
            } finally {
                warmingUrls.remove(url)
            }
        }
    }

    /**
     * Warm đúng một câu đầu của chương kế khi hàng PCM hiện tại đã đầy. Task nền
     * không chen trước câu đang phát; nếu vẫn đang chạy lúc chuyển chương, feeder
     * mới await chính Deferred này thay vì gửi request CapCut thứ hai.
     */
    private fun warmCapcutNextAudio(rawUrl: String?, token: Int): Boolean {
        if (token != playToken) return false
        val url = rawUrl?.takeIf { it.isNotBlank() } ?: return false
        val voice = s.selectedVoice
        if (!voice.startsWith("capcut:", ignoreCase = true)) return false
        val warm = warmedChapters[url] ?: run {
            warmChapter(url)
            return false
        }
        if (warm.firstChunkText.isBlank()) return false
        if (warm.bytes != null) return true

        val requestKey = warmAudioKey(url, voice)
        val resolvedKey = warmAudioKey(warm.chapterUrl, voice)
        synchronized(warmAudioTasks) {
            if (warmAudioTasks[requestKey] != null || warmAudioTasks[resolvedKey] != null) {
                return true
            }
            if (warmAudioTasks.size > 16) warmAudioTasks.clear()
            val epoch = warmEpoch.get()
            val storyId = s.activeStory?.id
            val task = viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val spoken = smartStore.applyPronunciation(warm.firstChunkText, storyId)
                    val body = JSONObject()
                        .put("text", spoken)
                        .put("voice_id", voice)
                        .put("speed", 1.0)
                        .put("pitch", 1.0)
                        .put("normalize", false)
                    val bytes = ReadingEngine.synthesize(body.toString())
                    val decoded = PcmDecoder.decode(
                        bytes,
                        tailSilenceMs = SpeechAudioPolicy.tailSilenceMs(warm.firstChunkText),
                    )
                    val result = WarmedAudio(bytes, decoded)
                    val updated = warm.copy(voice = voice, bytes = bytes, decoded = decoded)
                    synchronized(warmEpoch) {
                        if (epoch != warmEpoch.get()) return@async null
                        warmedChapters[url] = updated
                        warmedChapters[warm.chapterUrl] = updated
                    }
                    _ui.update { it.copy(prefetchStatus = "Chương kế tiếp và TTS đã sẵn sàng") }
                    result
                } catch (_: Exception) {
                    if (epoch != warmEpoch.get()) return@async null
                    _ui.update { it.copy(prefetchStatus = "Nội dung chương kế tiếp đã sẵn sàng") }
                    null
                }
            }
            warmAudioTasks[requestKey] = task
            warmAudioTasks[resolvedKey] = task
            task.start()
        }
        return true
    }

    private fun onChapterEnd() {
        markCurrentChapter(completed = true)
        // "Dung o cuoi cau" dat o cau cuoi chuong khong bao gio kich hoat trong
        // onPcmChunkStarted (khong con cau nao sau do): phai chan o day, neu
        // khong nguong cu ro sang chuong ke va app phat tiep ca chuong sau.
        if (stopAfterSentence) {
            stopAfterSentence=false;stopAfterSentenceAt=-1;releasePlayer()
            _ui.update{it.copy(isPlaying=false,isBuffering=false,sleepMode=SleepMode.OFF)}
            publishPlayback();persistProgress();return
        }
        if (stopAfterChapter) {
            stopAfterChapter=false;releasePlayer()
            _ui.update{it.copy(isPlaying=false,isBuffering=false,progress=0f,positionMs=0L,activeSentenceIndex=0,sleepMode=SleepMode.OFF)}
            resetProgressClock(0)
            publishPlayback();persistProgress();return
        }
        if (s.loop) { speakFrom(0); return }
        val next = adjacentChapterUrl(1)
        if (s.autoNextChapter && !next.isNullOrBlank()) {
            loadChapter(next, autoPlay = true, chapterDelta = 1)
        } else {
            releasePlayer()
            // Dua ve dau chuong: de nguyen activeSentenceIndex o cau cuoi thi
            // nut Play sau do chi phat lai dung cau cuoi roi dung.
            _ui.update { it.copy(isPlaying = false, isBuffering = false, progress = 0f, positionMs = 0L, activeSentenceIndex = 0) }
            resetProgressClock(0)
            publishPlayback()
            persistProgress()
        }
    }

    private fun startPositionTicker() {
        progressJob = viewModelScope.launch {
            while (isActive) {
                // 10 Hz is smooth enough visually; only the tiny progress leaves
                // collect these updates.
                // Khi pause, ha tan suat danh thuc 5 lan de tiet kiem CPU/pin va
                // tranh tranh lich voi RenderThread tren Android 16.
                delay(if (s.isPlaying) 120 else 800)
                if (!s.isPlaying) continue
                try {
                    if (!pcmActive || !pcmEngine.isRendering()) continue
                    val within = pcmEngine.withinChunkMs()
                    val index = s.activeSentenceIndex
                    if (progressClockToken != playToken) resetProgressClock(index)
                    val rawPos = positionMsFor(index, within)
                    val rawFraction = stableProgressFor(index, within)
                    val pos = maxOf(rawPos, progressFloorMs)
                    val frac = maxOf(rawFraction, progressFloorFraction)
                    progressFloorMs = pos
                    progressFloorFraction = frac
                    val dur = maxOf(durationMsTotal(), pos + 1L)
                    _progressUi.value = PlaybackProgressState(pos, dur, frac)
                    // Throttle (fix C1 hot path): the entire UiState is emitted here, so
                    // every screen recomposes on each emission. Skip when nothing the user
                    // can see changed — the clock ticks per second, the bar per ~0.5%.
                    val posSec = pos / 1000L
                    val fracBucket = (frac * 200f).toInt()
                    // Heavy UiState/media path only every 2s. Seek bar already has progressUi.
                    if (posSec == lastTickPosSec || posSec % 2L != 0L) continue
                    lastTickPosSec = posSec
                    lastTickFracBucket = fracBucket
                    // Single emission per visible change (fix M1): fold the media-notification
                    // metadata into the SAME update instead of a second publishPlayback() pass.
                    // Root fix for C1: do NOT copy activeStory here every tick. That churn used
                    // to invalidate the whole UiState (and every tab screen) each second. The
                    // enriched story is rebuilt from positionMs/progress at save time, so
                    // persisted progress is unaffected.
                    _ui.update { current ->
                        current.copy(
                            positionMs = pos,
                            durationMs = dur,
                            progress = frac,
                            playback = current.playback.copy(
                                positionMs = pos,
                                durationMs = dur.coerceAtLeast(1L),
                            ),
                        )
                    }
                    // ReaderActivity may be STOPPED while lock-screen, Bluetooth
                    // or notification controls remain visible. Push the cheap
                    // MediaSession state directly instead of relying on an
                    // Activity lifecycle collector that is suspended in background.
                    syncPlaybackService(s.playback)
                } catch (_: Exception) {}
            }
        }
    }

    private fun seekToMediaPosition(positionMs: Long) {
        val duration = maxOf(s.durationMs, _progressUi.value.durationMs, 1L)
        seekTo(positionMs.coerceIn(0L, duration).toFloat() / duration.toFloat())
    }

    fun seekTo(ratio: Float) {
        val r = ratio.coerceIn(0f, 1f)
        if (s.sentences.isEmpty()) return
        seekToSentence(indexForRatio(r))
    }

    /** Anh xa ti le THOI GIAN -> cau bang tong tien to chunkOffsets (cung mot
     *  thang do voi positionMs/durationMs), khong phai ti le theo so cau. */
    private fun indexForRatio(r: Float): Int {
        val size = s.sentences.size
        val total = durationMsTotal()
        if (total <= 0L) return (r * size).toInt().coerceIn(0, size - 1)
        val target = (r * total.toDouble()).toLong()
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chunkOffsets[mid] <= target) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun seekToSentence(target: Int) {
        val size = s.sentences.size
        if (size == 0) return
        val idx = target.coerceIn(0, size - 1)
        if (s.isPlaying) speakFrom(idx)
        else {
            restartOnResume = true
            val pos = positionMsFor(idx)
            val frac = progressFor(idx)
            _ui.update {
                it.copy(
                    activeSentenceIndex = idx,
                    progress = frac,
                    positionMs = pos,
                )
            }
            resetProgressClock(idx)
            publishPlayback()
        }
    }

    fun replay() {
        if (s.isPlaying) speakFrom(0)
        else {
            restartOnResume = true
            _ui.update { it.copy(activeSentenceIndex = 0, progress = 0f, positionMs = 0L) }
            resetProgressClock(0)
        }
    }

    /** Nhay chinh xac theo cau, dung cho menu ba cham va mini-player Loi truyen. */
    fun skipSentence(delta: Int) {
        val count = s.sentences.size
        if (count <= 0 || delta == 0) return
        val target = (s.activeSentenceIndex + delta).coerceIn(0, count - 1)
        seekToSentence(target)
    }

    fun toggleLoop() = _ui.update { it.copy(loop = !it.loop) }

    fun setSpeed(v: Float) {
        val newSpeed = v.coerceIn(0.5f, 3.0f)
        _ui.update { current ->
            current.copy(
                speed = newSpeed,
                sheet = null,
                durationMs = estimateChapterDuration(current.sentences, newSpeed),
            )
        }
        chunkDurations.clear()
        offsetsDirty = true
        // AudioTrack doi toc do duoc NGAY GIUA DONG PHAT, khong gian doan - day la
        // ly do duong PCM lien mach o moi toc do, khong chi 1.0x.
        if (pcmActive) pcmEngine.setSpeed(newSpeed)
        if (s.voiceProfileEnabled) s.activeStory?.id?.let { smartStore.saveProfile(it, s.selectedVoice, newSpeed) }
        persist()
        publishPlayback()
    }

    private fun estimateChapterDuration(sentences: List<String>, speed: Float): Long =
        sentences.sumOf { sentence -> estimateDurationMs(sentence, speed) }

    private suspend fun rampPcmVolume(from: Float, to: Float, durationMs: Long) {
        val steps = 8
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        for (step in 1..steps) {
            val ratio = step.toFloat() / steps.toFloat()
            pcmEngine.setVolume(from + (to - from) * ratio)
            delay(stepDelay)
        }
    }

    /** Chuẩn bị cả MP3 và PCM cho một giọng nhưng chưa đụng vào AudioTrack hiện tại. */
    private suspend fun prepareVoiceChunk(index: Int, voice: String, chapter: String): Boolean {
        val epoch = warmEpoch.get()
        if (chapter != chapterSig || index !in s.sentences.indices) return false
        val key = synthKeyFor(chapter, index, voice, s.sentences.getOrNull(index).orEmpty())
        if (pcmCache.get(key) != null) return true
        val bytes = synthesizeForVoice(index, voice, updateUi = false) ?: return false
        if (chapter != chapterSig) return false
        val decoded = PcmDecoder.decode(
            bytes,
            tailSilenceMs = SpeechAudioPolicy.tailSilenceMs(s.sentences.getOrNull(index).orEmpty()),
        )
        if (decoded == null) {
            audioCache.remove(key)
            return false
        }
        synchronized(warmEpoch) {
            if (epoch != warmEpoch.get() || chapter != chapterSig) return false
            pcmCache.put(key, decoded)
        }
        return true
    }

    private fun commitVoiceSelection(id: String) {
        _ui.update {
            it.copy(
                selectedVoice = id,
                sheet = null,
                prefetchStatus = "Đã chuyển giọng mượt",
                errorMessage = null,
            )
        }
        // Doi giong luc khong phat: hang doi engine con chua cac doan GIONG CU
        // da tong hop. Khong xoa thi resume() se play() tiep giong cu (co khi
        // het ca phan con lai cua chuong). Cache audio/pcm khong can xoa vi
        // synthKey da chua voice.
        if (!s.isPlaying) {
            if (pcmActive) pcmEngine.reset()
            restartOnResume = true
        }
        if (s.voiceProfileEnabled) {
            s.activeStory?.id?.let { smartStore.saveProfile(it, id, s.speed) }
        }
        persist()
        publishPlayback()
    }

    fun setVoice(rawId: String) {
        val id = OfflineVoicePolicy.migrate(rawId)
        if (id == s.selectedVoice) {
            _ui.update { it.copy(sheet = null) }
            return
        }
        voiceSwitchJob?.cancel()
        voiceSwitchJob = null
        volumeRampJob?.cancel()
        volumeRampJob = null
        if (pcmActive) pcmEngine.setVolume(s.playerVolume)

        val chapter = chapterSig
        val canSwitchLive = s.isPlaying && s.sentences.isNotEmpty() && chapter.isNotBlank()
        if (!canSwitchLive) {
            commitVoiceSelection(id)
            val index = s.activeSentenceIndex
            if (index in s.sentences.indices) {
                voiceSwitchJob = viewModelScope.launch(Dispatchers.IO) {
                    prepareVoiceChunk(index, id, chapter)
                }
            }
            return
        }

        // Giữ giọng cũ tiếp tục phát trong khi tạo/giải mã giọng mới.
        _ui.update {
            it.copy(sheet = null, prefetchStatus = "Đang chuẩn bị giọng mới…", errorMessage = null)
        }
        voiceSwitchJob = viewModelScope.launch(Dispatchers.IO) {
            var targetIndex = s.activeSentenceIndex.coerceIn(0, s.sentences.lastIndex)
            var prepared = false
            var attempts = 0
            while (isActive && chapter == chapterSig && attempts < 3) {
                prepared = prepareVoiceChunk(targetIndex, id, chapter)
                if (!prepared) break
                val latest = s.activeSentenceIndex.coerceIn(0, s.sentences.lastIndex)
                // Ở lượt cuối, giữ nguyên target vừa chuẩn bị; không cập nhật sang
                // một index chưa có PCM rồi vô tình tạo delay sau khi đã fade-out.
                if (latest <= targetIndex || attempts >= 2) break
                targetIndex = latest
                attempts++
            }
            if (!prepared || chapter != chapterSig) {
                if (chapter == chapterSig) {
                    val detail = TtsManager.lastError?.takeIf { it.isNotBlank() }
                        ?: "Không chuẩn bị được giọng mới; giọng hiện tại vẫn tiếp tục phát"
                    _ui.update { it.copy(prefetchStatus = null, errorMessage = detail) }
                }
                return@launch
            }

            // Người dùng có thể pause trong lúc chuẩn bị: đổi cài đặt nhưng không tự phát lại.
            if (!s.isPlaying) {
                withContext(Dispatchers.Main) { commitVoiceSelection(id) }
                return@launch
            }

            var switched = false
            try {
                rampPcmVolume(s.playerVolume, 0f, 96L)
                withContext(Dispatchers.Main) {
                    if (chapter != chapterSig) return@withContext
                    commitVoiceSelection(id)
                    if (s.isPlaying) {
                        voiceFadeInPending = true
                        speakFrom(targetIndex)
                    } else {
                        pcmEngine.setVolume(s.playerVolume)
                    }
                    switched = true
                }
            } finally {
                if (!switched) pcmEngine.setVolume(s.playerVolume)
            }
        }
    }

    // ---------- Gemini API key ----------
    /** Go phim trong o nhap: chi cap nhat tam, chua luu. */
    fun onGeminiKeyChange(value: String) {
        _ui.update { it.copy(geminiApiKey = value, geminiKeyStatus = null, geminiKeyOk = false) }
    }

    /**
     * Luu khoa roi GOI THU THAT mot cau ngan de biet khoa co dung khong,
     * thay vi de nguoi dung phat hien luc dang nghe giua chuong.
     */
    fun applyGeminiApiKey() {
        val key = s.geminiApiKey.trim()
        if (key.isBlank()) { clearGeminiApiKey(); return }
        _ui.update { it.copy(geminiApiKey = key, geminiKeyChecking = true, geminiKeyStatus = null) }
        viewModelScope.launch(Dispatchers.IO) {
            ensureReady()
            TramTruyenApplication.saveGeminiApiKey(getApplication<Application>(), key)
            // Không dùng kết quả gọi thử để đánh dấu “chưa nhận key”: nhiều máy/mạng
            // chặn request thử hoặc key chưa bật quota TTS nhưng app đã lưu và provider
            // đã hiện giọng Gemini. Trạng thái hợp lệ ở đây nghĩa là key đã được nhận,
            // lưu và nạp vào TtsManager; lỗi phát thật sẽ báo khi tổng hợp âm thanh.
            val testOk = try {
                GeminiTtsProvider(key).synthesize("Xin chào", "gemini:Kore", 1.0, 1.0) != null
            } catch (_: Exception) {
                false
            }
            loadVoices()
            val count = s.voiceGroups.firstOrNull { group ->
                group.name.contains("Gemini", ignoreCase = true)
            }?.voices?.size ?: 0
            _ui.update {
                it.copy(
                    geminiKeyChecking = false,
                    geminiKeyOk = true,
                    geminiKeyStatus = if (testOk) {
                        "Đã nhận khoá. Đã bật $count giọng Gemini trong mục chọn giọng."
                    } else {
                        "Đã nhận và lưu khoá. Đã bật $count giọng Gemini; nếu chưa phát được, kiểm tra hạn mức hoặc kết nối mạng."
                    },
                )
            }
        }
    }

    /** Xoa khoa: an giong Gemini va tra giong dang chon ve mac dinh neu can. */
    fun clearGeminiApiKey() {
        _ui.update { it.copy(geminiApiKey = "", geminiKeyChecking = true, geminiKeyStatus = null, geminiKeyOk = false) }
        viewModelScope.launch(Dispatchers.IO) {
            ensureReady()
            TramTruyenApplication.saveGeminiApiKey(getApplication<Application>(), "")
            loadVoices()
            withContext(Dispatchers.Main) {
                if (s.selectedVoice.startsWith("gemini:")) setVoice(TtsManager.DEFAULT_VOICE)
                _ui.update {
                    it.copy(
                        geminiKeyChecking = false,
                        geminiKeyStatus = "Đã xoá khoá. Các giọng Gemini được ẩn khỏi danh sách.",
                    )
                }
            }
        }
    }


    // ---------- Zalo AI API key ----------
    fun onZaloKeyChange(value: String) {
        _ui.update { it.copy(zaloApiKey = value, zaloKeyStatus = null, zaloKeyOk = false) }
    }

    fun applyZaloApiKey() {
        val key = s.zaloApiKey.trim()
        if (key.isBlank()) { clearZaloApiKey(); return }
        _ui.update { it.copy(zaloApiKey = key, zaloKeyChecking = true, zaloKeyStatus = null) }
        viewModelScope.launch(Dispatchers.IO) {
            ensureReady()
            TramTruyenApplication.saveZaloApiKey(getApplication<Application>(), key)
            val ok = try { ZaloTtsProvider(key).synthesize("Xin chào", "zalo:2", 1.0, 1.0) != null }
                catch (_: Exception) { false }
            loadVoices()
            _ui.update { it.copy(
                zaloKeyChecking = false,
                zaloKeyOk = ok,
                zaloKeyStatus = if (ok) "Khoá hợp lệ. Đã bật 4 giọng Zalo AI."
                    else "Đã lưu khoá nhưng gọi thử thất bại. Kiểm tra khoá, hạn mức hoặc mạng.",
            ) }
        }
    }

    fun clearZaloApiKey() {
        _ui.update { it.copy(zaloApiKey = "", zaloKeyChecking = true, zaloKeyStatus = null, zaloKeyOk = false) }
        viewModelScope.launch(Dispatchers.IO) {
            ensureReady()
            TramTruyenApplication.saveZaloApiKey(getApplication<Application>(), "")
            loadVoices()
            withContext(Dispatchers.Main) {
                if (s.selectedVoice.startsWith("zalo:")) setVoice(TtsManager.DEFAULT_VOICE)
                _ui.update { it.copy(zaloKeyChecking = false, zaloKeyStatus = "Đã xoá khoá Zalo AI.") }
            }
        }
    }

    fun openSheet(name: String?) = _ui.update { it.copy(sheet = if (it.sheet == name) null else name) }
    fun closeSheet() = _ui.update { it.copy(sheet = null) }
    fun clearError() = _ui.update { it.copy(errorMessage = null) }

    // ---------- settings ----------
    fun setUiTheme(t: UiTheme) { _ui.update { it.copy(uiTheme = t) }; persist() }
    fun setColorMode(mode: ColorMode) {
        _ui.update { it.copy(colorMode = mode, monetTheme = true) }
        persist()
    }
    @Deprecated("Use setColorMode") fun toggleMonet() {
        val next = if (s.colorMode == ColorMode.CUSTOM_SEED) ColorMode.SYSTEM_WALLPAPER else ColorMode.CUSTOM_SEED
        setColorMode(next)
    }
    fun setSeed(name: String) {
        _ui.update { it.copy(monetSeed = name, colorMode = ColorMode.CUSTOM_SEED, monetTheme = true) }
        persist()
    }
    fun setFrameRateMode(mode: FrameRateMode) {
        _ui.update { it.copy(frameRateMode = mode) }
        persist()
    }
    fun setHapticEnabled(enabled: Boolean) {
        _ui.update { it.copy(hapticEnabled = enabled) }
        persist()
    }
    fun setHapticIntensity(value: Int) {
        _ui.update { it.copy(hapticIntensity = value.coerceIn(1, 100)) }
        persist()
    }
    fun setKaraokeFont(px: Int) { _ui.update { it.copy(karaokeFontSize = px.coerceIn(16, 48)) }; persist() }
    fun setPlayerVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        _ui.update { it.copy(playerVolume = volume) }
        if (pcmActive) {
            volumeRampJob?.cancel()
            volumeRampJob = null
            voiceFadeInPending = false
            val effective = if (sleepSeconds in 1..30) volume * sleepSeconds / 30f else volume
            pcmEngine.setVolume(effective)
        }
        persist()
    }

    fun setPlayerBlurLive(value: Int) {
        val v = value.coerceIn(0, 60)
        if (s.playerBlur == v) return
        _ui.update { it.copy(playerBlur = v) }
    }
    fun setPlayerBlur(value: Int) {
        val v = value.coerceIn(0, 60)
        _ui.update { it.copy(playerBlur = v) }
        persist()
    }
    fun toggleVoiceProfile() { _ui.update { it.copy(voiceProfileEnabled=!it.voiceProfileEnabled) }; persist() }

    /** Danh sach nguon giong cho man Cai dat: ten, so giong, da cau hinh chua, dang bat/tat. */
    private fun refreshTtsSources() {
        val disabled = s.disabledTtsSources
        val infos = try { TtsManager.providerInfo() } catch (_: Exception) { emptyList() }
        val byKey = infos.associateBy { it.key.lowercase() }
        val catalog = listOf(
            "android" to "Android TTS (ngoại tuyến)",
            "edge" to "Edge TTS",
            "capcut" to "CapCut TTS (vi-VN)",
            "gtts" to "Google Translate TTS",
            "tiktok" to "TikTok TTS (trực tuyến)",
            "offline" to "AI ngoại tuyến",
            "gemini" to "Gemini AI",
            "zalo" to "Zalo AI TTS",
        )
        val rows = catalog.mapNotNull { (key, name) ->
            val info = byKey[key]
            // Gemini/Zalo chi la nguon khi da co API key: providerInfo chi liet ke
            // khi da cau hinh; neu TtsManager chua san sang thi dua vao co key da luu.
            if (key == "gemini" || key == "zalo") {
                val keySaved = if (key == "gemini") s.geminiKeyOk else s.zaloKeyOk
                if (info == null && !keySaved) return@mapNotNull null
            }
            TtsSourceUi(
                key = key,
                name = info?.name?.takeIf { it.isNotBlank() && it != key } ?: name,
                voices = info?.voices ?: 0,
                configured = info?.configured ?: true,
                enabled = key !in disabled,
            )
        }
        _ui.update { it.copy(ttsSources = rows) }
    }

    /**
     * Bat/tat mot nguon giong doc. Tat nguon se an het giong cua nguon do khoi
     * danh sach chon giong va chan tong hop bang nguon do; giong dang chon
     * thuoc nguon vua tat thi tu chuyen sang giong cua nguon con bat.
     * Khong cho tat nguon cuoi cung con bat.
     */
    fun toggleTtsSource(key: String, enabled: Boolean) {
        val k = key.lowercase()
        val current = s.disabledTtsSources
        val next = if (enabled) current - k else current + k
        if (next == current) return
        if (!enabled) {
            val allKeys = s.ttsSources.map { it.key }.ifEmpty {
                listOf("android", "edge", "capcut", "gtts", "tiktok", "offline")
            }
            if ((allKeys - next).isEmpty()) {
                _ui.update { it.copy(errorMessage = "Phải bật ít nhất một nguồn giọng đọc") }
                return
            }
        }
        _ui.update { it.copy(disabledTtsSources = next) }
        TtsManager.setDisabledProviders(next)
        persist()
        loadVoices()
        refreshTtsSources()
        if (!enabled && s.selectedVoice.substringBefore(':').lowercase() == k) {
            val fallback = firstEnabledVoiceKey(next)
            if (fallback != null && fallback != s.selectedVoice) setVoice(fallback)
        }
    }

    private fun firstEnabledVoiceKey(disabled: Set<String>): String? {
        if ("gtts" !in disabled) return TtsManager.DEFAULT_VOICE
        return try {
            TtsManager.getAllVoices().firstOrNull()
                ?.get("voice_id")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) { null }
    }
    fun setContentLanguage(value: String) {
        _ui.update { it.copy(contentLanguage = value) }
        ReadingPreferences.update(value, s.contentExtractionMode)
        audioCache.clear()
        pcmCache.clear()
        persist()
    }
    fun setContentExtractionMode(value: String) {
        _ui.update { it.copy(contentExtractionMode = value) }
        ReadingPreferences.update(s.contentLanguage, value)
        persist()
    }
    fun addPronunciation(from:String,to:String,onlyCurrent:Boolean) {
        val sid=if(onlyCurrent)s.activeStory?.id else null
        _ui.update { it.copy(pronunciationRules=smartStore.addRule(from,to,sid),backupStatus="Đã thêm cách đọc") }
        audioCache.clear()
        pcmCache.clear()
    }
    fun removePronunciation(id:String) { _ui.update { it.copy(pronunciationRules=smartStore.removeRule(id)) }; audioCache.clear(); pcmCache.clear() }
    fun exportBackupJson():String = JSONObject().put("format",3).put("store",JSONObject(Store.exportBackup()))
        .put("features",smartStore.exportJson()).put("settings",JSONObject().put("voice",s.selectedVoice)
            .put("playerBlur",s.playerBlur).put("volume",s.playerVolume).put("voiceProfile",s.voiceProfileEnabled)
            .put("contentLanguage",s.contentLanguage).put("contentExtractionMode",s.contentExtractionMode)).toString(2)
    fun importBackupJson(raw:String) { viewModelScope.launch(Dispatchers.IO) { try {
        val o=JSONObject(raw); Store.importBackup(o.getJSONObject("store").toString()); smartStore.importJson(o.optJSONObject("features")?:JSONObject())
        val set=o.optJSONObject("settings"); _ui.update {
            val rawImportedVoice=set?.optString("voice",it.selectedVoice)?:it.selectedVoice
            val safeVoice=OfflineVoicePolicy.migrate(rawImportedVoice)
            it.copy(selectedVoice=safeVoice,
            playerBlur=(set?.optInt("playerBlur",it.playerBlur)?:it.playerBlur).coerceIn(0,100),playerVolume=(set?.optDouble("volume",it.playerVolume.toDouble())?.toFloat()?:it.playerVolume).coerceIn(0f,1f),voiceProfileEnabled=set?.optBoolean("voiceProfile",it.voiceProfileEnabled)?:it.voiceProfileEnabled,
            contentLanguage=set?.optString("contentLanguage",it.contentLanguage)?:it.contentLanguage,
            contentExtractionMode=set?.optString("contentExtractionMode",it.contentExtractionMode)?:it.contentExtractionMode,
            pronunciationRules=smartStore.rules(),backupStatus="Khôi phục thành công") }
        ReadingPreferences.update(s.contentLanguage, s.contentExtractionMode)
        persist(); refreshCollections()
    } catch(e:Exception){ _ui.update{it.copy(backupStatus="File sao lưu không hợp lệ: ${e.message}")} } } }
    fun toggleAutoNext() { _ui.update { it.copy(autoNextChapter = !it.autoNextChapter) }; persist() }
    fun setSleepTimer(minutes: Int?) {
        stopAfterSentence=false;stopAfterChapter=false
        _ui.update { it.copy(sleepTimer = minutes, sleepMode=if(minutes==null)SleepMode.OFF else SleepMode.MINUTES) }
        sleepSeconds = (minutes ?: 0) * 60
    }
    fun setSmartSleep(mode:SleepMode) {
        sleepSeconds=0;stopAfterSentence=mode==SleepMode.END_SENTENCE;stopAfterChapter=mode==SleepMode.END_CHAPTER
        stopAfterSentenceAt=if(stopAfterSentence)s.activeSentenceIndex else -1
        if(pcmActive)pcmEngine.setVolume(s.playerVolume)
        _ui.update{it.copy(sleepTimer=null,sleepMode=mode)}
    }

    // ---------- progress / history / library ----------
    private fun markCurrentChapter(completed: Boolean) {
        if (s.sentences.isEmpty()) return
        val st = s.activeStory ?: return
        val url = st.chapterUrl ?: return
        val chapterId = StoryIds.chapterId(url, s.chapterNumber)
        val prog = if (completed) 1f else progressFor(s.activeSentenceIndex)
        val status = if (completed || prog >= 0.95f) ChapterListenStatus.COMPLETED else ChapterListenStatus.IN_PROGRESS
        val generation = Store.writeGeneration()
        // Chup gia tri NGAY tai day: khoi IO ben duoi chay sau, neu doc s song
        // thi nextChapter() nap chuong moi xong truoc se lam ban ghi cua
        // chuong cu mang tieu de/so chuong/tien do cua chuong moi.
        val capTitle = s.chapterTitle
        val capNumber = s.chapterNumber
        val capSentenceIndex = s.activeSentenceIndex
        val capSentenceCount = s.sentences.size
        val chapterKey = StoryIds.normalizeUrl(url)
        viewModelScope.launch(Dispatchers.IO) {
            Store.withWriteGeneration(generation) {
            try {
                Store.upsertChapterHistory(st.id, buildJsonObject {
                    put("chapterId", JsonPrimitive(chapterId))
                    put("chapterUrl", JsonPrimitive(url))
                    put("chapterTitle", JsonPrimitive(capTitle))
                    put("chapterIndex", JsonPrimitive(capNumber))
                    put("lastSentenceIndex", JsonPrimitive(capSentenceIndex))
                    put("sentenceCount", JsonPrimitive(capSentenceCount))
                    put("progress", JsonPrimitive(prog.toDouble()))
                    put("status", JsonPrimitive(status.name))
                })
            } catch (_: Exception) {}
            }
            // refresh chapter items status in UI
            _ui.update { ui ->
                if (ui.activeStory?.id != st.id || ui.chapterItems.isEmpty()) return@update ui
                ui.copy(chapterItems = ui.chapterItems.map { item ->
                    if (StoryIds.normalizeUrl(item.url) == chapterKey)
                        item.copy(status = status, progress = prog, lastListenedAt = System.currentTimeMillis())
                    else item
                })
            }
        }
    }

    private fun saveProgressAndHistory(
        story: Story,
        chapterId: String,
        chapterTitle: String,
        sentenceIndex: Int,
        sentenceCount: Int,
        completed: Boolean,
    ) {
        val sentenceProgress = if (sentenceCount <= 0) 0f else sentenceIndex.toFloat() / sentenceCount
        val timedProgress = if (s.durationMs > 0L) {
            (s.positionMs.toFloat() / s.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            s.progress.coerceIn(0f, 1f)
        }
        val prog = if (completed) 1f else maxOf(sentenceProgress, timedProgress)
        val status = if (completed || prog >= 0.95f) ChapterListenStatus.COMPLETED else ChapterListenStatus.IN_PROGRESS
        val enriched = story.copy(
            // Fix G: coerceAtLeast(story.currentChapter) khien so chuong chi tang,
            // khong bao gio giam - lui chuong xong van luu so cua chuong cao nhat.
            currentChapter = (if (story.currentChapter > 0) story.currentChapter else s.chapterNumber).coerceAtLeast(1),
            chapterTitle = chapterTitle,
            sentenceIndex = sentenceIndex,
            sentenceCount = sentenceCount,
            progress = prog,
            positionMs = s.positionMs,
            updatedAt = System.currentTimeMillis(),
            lastListenedAt = System.currentTimeMillis(),
            totalChapters = story.totalChapters.coerceAtLeast(s.tocUrls.size),
        )
        _ui.update { current ->
            val exists = current.libraryItems.any { saved -> sameStory(saved, enriched) }
            val nextLibrary = if (exists) current.libraryItems.map { saved ->
                if (sameStory(saved, enriched)) {
                    enriched
                } else saved
            } else listOf(enriched) + current.libraryItems
            current.copy(
                activeStory = enriched,
                libraryItems = nextLibrary,
                chapterItems = if (enriched.sourceType == StorySourceType.EPUB) {
                    current.chapterItems.map { item ->
                        if (item.index == enriched.currentChapter && item.url == enriched.chapterUrl) {
                            item.copy(status = status, progress = prog, lastListenedAt = enriched.lastListenedAt)
                        } else item
                    }
                } else current.chapterItems,
            )
        }
        val generation = Store.writeGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            Store.withWriteGeneration(generation) {
            try {
                ReadingEngine.historyUpsert(storyToJson(enriched))
                Store.upsertProgress(enriched.id, buildJsonObject {
                    put("chapterId", JsonPrimitive(chapterId))
                    put("chapterUrl", JsonPrimitive(enriched.chapterUrl ?: ""))
                    put("chapterTitle", JsonPrimitive(chapterTitle))
                    put("chapterIndex", JsonPrimitive(enriched.currentChapter))
                    put("sentenceIndex", JsonPrimitive(sentenceIndex))
                    put("sentenceCount", JsonPrimitive(sentenceCount))
                    put("progress", JsonPrimitive(prog.toDouble()))
                    put("positionMs", JsonPrimitive(enriched.positionMs))
                })
                Store.upsertChapterHistory(enriched.id, buildJsonObject {
                    put("chapterId", JsonPrimitive(chapterId))
                    put("chapterUrl", JsonPrimitive(enriched.chapterUrl ?: ""))
                    put("chapterTitle", JsonPrimitive(chapterTitle))
                    put("chapterIndex", JsonPrimitive(enriched.currentChapter))
                    put("lastSentenceIndex", JsonPrimitive(sentenceIndex))
                    put("sentenceCount", JsonPrimitive(sentenceCount))
                    put("progress", JsonPrimitive(prog.toDouble()))
                    put("status", JsonPrimitive(status.name))
                })
                // Always keep the story in the library so it appears under "Thư viện".
                ReadingEngine.libraryUpsert(storyToJson(enriched))
                if (Store.isInLibrary(storyIdentity(enriched))) {
                    Store.updateLibraryProgress(storyIdentity(enriched), buildJsonObject {
                        put("currentChapter", JsonPrimitive(enriched.currentChapter))
                        put("chapterIndex", JsonPrimitive(enriched.currentChapter))
                        put("chapterUrl", JsonPrimitive(enriched.chapterUrl ?: ""))
                        put("chapterTitle", JsonPrimitive(chapterTitle))
                        put("sentenceIndex", JsonPrimitive(sentenceIndex))
                        put("sentenceCount", JsonPrimitive(sentenceCount))
                        put("progress", JsonPrimitive(prog.toDouble()))
                        put("positionMs", JsonPrimitive(enriched.positionMs))
                        put("totalChapters", JsonPrimitive(enriched.totalChapters))
                        put("nextUrl", JsonPrimitive(enriched.nextUrl ?: ""))
                        put("prevUrl", JsonPrimitive(enriched.prevUrl ?: ""))
                        put("coverUrl", JsonPrimitive(enriched.coverUrl))
                        put("title", JsonPrimitive(enriched.title))
                    })
                }
            } catch (_: Exception) {}
            }
            refreshCollections()
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(12_000)
            persistProgress()
        }
    }

    private fun persistProgress() {
        val st = s.activeStory ?: return
        val url = st.chapterUrl ?: return
        if (s.sentences.isEmpty()) return
        saveProgressAndHistory(
            story = st,
            chapterId = StoryIds.chapterId(url, s.chapterNumber),
            chapterTitle = s.chapterTitle,
            sentenceIndex = s.activeSentenceIndex,
            sentenceCount = s.sentences.size,
            completed = false,
        )
    }

    /** Called from Activity.onStop / process lifecycle to flush progress without pausing. */
    fun forceSaveProgress() = persistProgress()

    fun saveToLibrary(story: Story) {
        val latest = s.activeStory?.takeIf { active ->
            sameStory(active, story) && StoryIds.normalizeUrl(active.chapterUrl) == StoryIds.normalizeUrl(story.chapterUrl)
        } ?: story
        _ui.update { current ->
            current.copy(
                libraryItems = listOf(latest) + current.libraryItems.filterNot { saved ->
                    sameStory(saved, latest)
                }
            )
        }
        val generation = Store.writeGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            Store.withWriteGeneration(generation) {
                ReadingEngine.libraryUpsert(storyToJson(latest))
            }
            refreshCollections()
        }
    }

    fun deleteFromLibrary(id: String) = viewModelScope.launch {
        val story = (s.libraryItems + s.historyItems + listOfNotNull(s.activeStory))
            .firstOrNull { it.id == id } ?: return@launch
        val deletingActive = s.activeStory?.let { sameStory(it, story) } == true
        val identity = storyIdentity(story)
        val urls = StoryIds.aliases(story.id, story.storyUrl, story.chapterUrl,
            story.localBookId, story.title, story.source).filter { it.startsWith("http") }
            .map(StoryIds::normalizeUrl).toSet()
        if (deletingActive) {
            persistJob?.cancel()
            chapterLoadJob?.cancel()
            retryChapterLoad = null
            openFirstChapterJob?.cancel()
            stopFromMedia()
            _ui.update { it.copy(activeStory = null, isPlayerOpen = false, chapterLoadError = null,
                sentences = emptyList(), tocUrls = emptyList(), tocTitles = emptyList(),
                chapterItems = emptyList(), chapterTitle = "", chapterNumber = 1,
                activeSentenceIndex = 0, positionMs = 0, durationMs = 0, progress = 0f,
                playback = PlaybackMetadata(), sheet = null, hiddenMiniPlayerStoryId = null) }
        }
        // Cache keys are shared/text-based: clear disposable caches, never other books/settings.
        synchronized(warmEpoch) {
        warmEpoch.incrementAndGet()
        warmAudioTasks.values.forEach { it.cancel() }
        warmAudioTasks.clear(); warmedChapters.clear(); warmingUrls.clear()
        audioCache.clear(); pcmCache.clear()
        if (deletingActive) { chunkDurations.clear(); chapterSig = ""; chunkOffsets = LongArray(1) }
        synthInFlight.values.forEach { it.cancel() }; synthInFlight.clear()
        }
        suggestionsJob?.cancel(); webSearchJob?.cancel(); autoSearchJob?.cancel()
        try {
            val ids = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val removedIds = Store.deleteStory(identity)
                ReadingEngine.clearStoryCaches()
                if (story.sourceType == StorySourceType.EPUB && story.localBookId != null)
                    check(vn.tramtruyen.reader.engine.ebooks.EpubParser.delete(story.localBookId)) { "Không xoá được tệp EPUB" }
                smartStore.deleteStories(removedIds + story.id)
                vn.tramtruyen.reader.engine.sources.BrowserHistoryManager.deleteStoryUrls(urls +
                    removedIds.filter { it.startsWith("http") }.map(StoryIds::normalizeUrl))
                val images = coil.Coil.imageLoader(getApplication())
                // Cancel in-flight covers before eviction so they cannot refill the deleted cache.
                images.shutdown()
                images.memoryCache?.clear()
                images.diskCache?.clear()
                coil.Coil.setImageLoader(images.newBuilder().build())
                removedIds
            }
            vn.tramtruyen.reader.engine.sources.BrowserSession.clearStoryData(urls +
                ids.filter { it.startsWith("http") }.map(StoryIds::normalizeUrl))
            refreshCollections()
            _ui.update { current -> current.copy(storyToDelete = null,
                libraryItems = current.libraryItems.filterNot { sameStory(it, story) },
                historyItems = current.historyItems.filterNot { sameStory(it, story) },
                pronunciationRules = smartStore.rules(), prefetchStatus = null,
                webResults = emptyList(), webSuggestions = emptyList(), webSearching = false,
                webSuggestionsLoading = false, webOpeningUrl = null,
                paste = PasteState(), epubImport = EpubImportState()) }
        } catch (e: Exception) {
            _ui.update { it.copy(storyToDelete = null,
                errorMessage = "Chưa xoá hết dữ liệu truyện: ${e.message}. Hãy thử lại.") }
        }
    }

    fun removeFromHistory(id: String) = viewModelScope.launch(Dispatchers.IO) {
        try { ReadingEngine.historyRemove(id) } catch (_: Exception) {}
        refreshCollections()
    }

    fun askDelete(story: Story?) = _ui.update { it.copy(storyToDelete = story) }

    // ---------- tim kiem ----------
    // Tim kiem CUC BO tren du lieu da co san tren may (thu vien, lich su,
    // danh sach chuong cua truyen dang mo). Khong cham mang, khong the hong
    // khi site nguon doi HTML.

    /** Load the configured source list without prewarming/requesting every website. */
    fun prepareWebSearchSources() {
        if (s.webSearchSources.isNotEmpty()) return
        viewModelScope.launch {
            ensureReady()
            val domains = withContext(Dispatchers.IO) { ReadingEngine.searchSourceDomains() }
            _ui.update { current ->
                val chosen = current.selectedWebSearchSource
                    .takeIf { it in domains }
                    ?: domains.firstOrNull().orEmpty()
                current.copy(
                    webSearchSources = domains,
                    selectedWebSearchSource = chosen,
                )
            }
            loadWebSuggestions(s.selectedWebSearchSource)
        }
    }

    private var suggestionsJob: Job? = null

    fun loadWebSuggestions(domain: String = s.selectedWebSearchSource) {
        if (domain.isBlank() || domain !in s.webSearchSources) return
        suggestionsJob?.cancel()
        _ui.update {
            it.copy(webSuggestions = emptyList(), webSuggestionsSource = domain,
                webSuggestionsLoading = true, webSuggestionsError = null)
        }
        suggestionsJob = viewModelScope.launch {
            try {
                val hits = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        ReadingEngine.sourceSuggestions(domain)
                    } ?: throw IllegalStateException("Suggestion request timed out")
                }
                if (s.selectedWebSearchSource == domain) {
                    _ui.update {
                        it.copy(webSuggestions = hits.map { hit ->
                            WebSearchHit(title = hit.title, url = hit.url,
                                coverUrl = hit.coverUrl, author = hit.author, source = hit.source)
                        }, webSuggestionsLoading = false,
                            webSuggestionsError = if (hits.isEmpty()) "Trang này chưa có đề xuất khả dụng." else null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (s.selectedWebSearchSource == domain) {
                    _ui.update {
                        it.copy(webSuggestionsLoading = false,
                            webSuggestionsError = "Không tải được đề xuất từ trang web. Thử lại nhé.")
                    }
                }
            }
        }
    }

    /** Selecting another source clears old results and searches only that source. */
    fun selectWebSearchSource(domain: String) {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank() || normalized !in s.webSearchSources) return
        if (normalized == s.selectedWebSearchSource) return
        autoSearchJob?.cancel()
        webSearchJob?.cancel()
        val query = s.searchQuery.trim()
        _ui.update {
            it.copy(
                selectedWebSearchSource = normalized,
                webResults = emptyList(),
                webError = null,
                webSearching = false,
                webSearchedQuery = "",
                webSearchedSource = "",
            )
        }
        loadWebSuggestions(normalized)
        if (query.length >= 2 && !isLinkQuery(query)) searchWeb(query, normalized)
    }

    /** Search the selected web only. Cancel the prior query before displaying new results. */
    fun setSearchQuery(q: String) {
        if (q == s.searchQuery) return
        autoSearchJob?.cancel()
        webSearchJob?.cancel()
        _ui.update { it.copy(searchQuery = q) }
        val key = q.trim()
        if (key.length < 2 || isLinkQuery(key)) {
            _ui.update {
                it.copy(webResults = emptyList(), webError = null, webSearching = false,
                    webSearchedQuery = "", webSearchedSource = "")
            }
            return
        }
        if (key.equals(s.webSearchedQuery, ignoreCase = true) &&
            s.selectedWebSearchSource == s.webSearchedSource && s.webResults.isNotEmpty()
        ) return
        _ui.update {
            it.copy(webResults = emptyList(), webError = null, webSearching = false,
                webSearchedQuery = "", webSearchedSource = "")
        }
        autoSearchJob = viewModelScope.launch {
            delay(AUTO_SEARCH_DEBOUNCE_MS)
            lastPartialPushAt = 0L
            searchWeb(key)
        }
    }

    /** Nguoi dung dan link thay vi go tu khoa? */
    fun isLinkQuery(q: String): Boolean {
        val t = q.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")
    }

    fun searchStories(q: String): List<Story> {
        val k = q.trim().lowercase()
        if (k.isEmpty()) return emptyList()
        return allStories()
            .filter { st -> st.id != placeholderStory.id }
            .filter { st ->
                st.title.lowercase().contains(k) ||
                    st.author.lowercase().contains(k) ||
                    st.source.orEmpty().lowercase().contains(k)
            }
    }

    fun searchChapters(q: String): List<ChapterUiModel> {
        val k = q.trim().lowercase()
        if (k.isEmpty()) return emptyList()
        return s.chapterItems.filter { item -> item.title.lowercase().contains(k) }.take(30)
    }

    /** Dan link vao o tim kiem -> mo thang bang phan tich link san co. */
    fun analyzeFromSearch(q: String) {
        val url = q.trim()
        if (url.isEmpty()) return
        _ui.update { it.copy(pasteOpen = true, paste = PasteState(url = url)) }
        analyzeLink(url)
    }

    /** Han gio tuyet doi cho 1 lan tim tren web (tinh ca moi tang ben duoi). */
    private val WEB_SEARCH_TIMEOUT_MS = 30_000L

    /** Query only the selected source, never fan out across the source catalog. */
    fun searchWeb(query: String, sourceDomain: String? = null) {
        val key = query.trim()
        if (key.isBlank() || isLinkQuery(key)) return
        webSearchJob?.cancel()
        _ui.update { it.copy(webSearching = true, webError = null) }
        webSearchJob = viewModelScope.launch {
            try {
                ensureReady()
                val domains = s.webSearchSources.ifEmpty {
                    withContext(Dispatchers.IO) { ReadingEngine.searchSourceDomains() }
                }
                val selected = (sourceDomain ?: s.selectedWebSearchSource)
                    .takeIf { it in domains }
                    ?: domains.firstOrNull()
                    ?: throw RuntimeException("Chưa có nguồn web tìm kiếm khả dụng.")
                _ui.update {
                    it.copy(
                        webSearchSources = domains,
                        selectedWebSearchSource = selected,
                    )
                }
                // CHOT CHAN CUOI: du tang duoi co treo the nao, sau 18s la dung han,
                // khong de vong xoay quay mai.
                val res = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(WEB_SEARCH_TIMEOUT_MS) {
                        // HIEN DAN: moi lan mot nguon tra ket qua ve la day thang len man hinh,
                        // khong doi cac nguon con lai. Dong dau tien thuong xuat hien sau ~0.5s.
                        JSONObject(
                            ReadingEngine.searchStream(
                                query = key,
                                genre = null,
                                onlyDomains = listOf(selected),
                                onPartial = { partialJson ->
                                val partial = parseWebHits(JSONObject(partialJson))
                                // BO QUA ket qua cu rich: nguoi dung da go tu khoa khac roi.
                                val stillWanted = _ui.value.searchQuery.trim()
                                    .equals(key, ignoreCase = true)
                                    && _ui.value.selectedWebSearchSource == selected
                                // GIAM GIAT: 72 nguon tra ve doi nhau, neu lan nao cung ve
                                // lai giao dien thi danh sach nhay lien tuc. Chi cho phep
                                // cap nhat cach nhau it nhat PARTIAL_UI_MIN_GAP_MS.
                                val now = android.os.SystemClock.elapsedRealtime()
                                val paced = now - lastPartialPushAt >= PARTIAL_UI_MIN_GAP_MS
                                if (paced) lastPartialPushAt = now
                                if (partial.isNotEmpty() && stillWanted && paced) {
                                    _ui.update { st ->
                                        st.copy(
                                            webResults = partial,
                                            webSearchedQuery = key,
                                            webSearchedSource = selected,
                                            webError = null,
                                            // van de vong xoay chay: con nguon dang tim tiep
                                            webSearching = true,
                                        )
                                    }
                                }
                                },
                            )
                        )
                    }
                } ?: throw RuntimeException("Các trang nguồn phản hồi quá chậm. Bấm tìm lại giúp mình nhé.")
                if (_ui.value.selectedWebSearchSource != selected ||
                    !_ui.value.searchQuery.trim().equals(key, ignoreCase = true)
                ) return@launch
                val parsed = parseWebHits(res)
                val failedArray = res.optJSONArray("failed_sites") ?: org.json.JSONArray()
                val failedDomains = (0 until failedArray.length()).mapNotNull { i ->
                    failedArray.optString(i).takeIf { it.isNotBlank() }
                }.toSet()
                if (failedDomains.isNotEmpty()) {
                    _ui.update { it.copy(webUnavailableSources = it.webUnavailableSources + failedDomains) }
                }
                // KHONG BAO GIO XOA TRANG: neu vong cuoi ra it hon nhung gi da hien
                // (nguon bi huy giua chung, mang chap chon), giu lai ket qua da co.
                val shown = _ui.value.webResults
                val list = if (parsed.isEmpty() && shown.isNotEmpty() &&
                    _ui.value.webSearchedQuery.equals(key, ignoreCase = true)
                ) shown else parsed
                _ui.update {
                    it.copy(
                        webSearching = false,
                        webResults = list,
                        webSearchedQuery = key,
                        webSearchedSource = selected,
                        webSitesSearched = res.optInt("searched_sites", 0),
                        webError = if (list.isEmpty()) {
                            val failedArr = res.optJSONArray("failed_sites") ?: org.json.JSONArray()
                            val failed = failedArr.length()
                            val total = res.optInt("searched_sites", 0)
                            if (failed > 0 && failed >= total) {
                                "Không nguồn nào phản hồi. Có thể mất mạng hoặc nguồn đang bị chặn."
                            } else {
                                "Không thấy truyện nào khớp với \"$key\". Đã quét $total nguồn, $failed nguồn không phản hồi."
                            }
                        } else null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Het gio hoac loi mang: GIU NGUYEN nhung ket qua da hien duoc.
                // Truoc day cho nay xoa sach danh sach nen ket qua vua hien lai bien mat.
                _ui.update { st ->
                    val keep = st.webResults
                    st.copy(
                        webSearching = false,
                        webResults = keep,
                        webError = if (keep.isEmpty()) {
                            e.message ?: "Không kết nối được tới trang nguồn."
                        } else null,
                    )
                }
            }
        }
    }

    /** Doc mang "results" trong JSON tim kiem thanh danh sach hien thi. */
    private fun parseWebHits(res: JSONObject): List<WebSearchHit> {
        val arr = res.optJSONArray("results") ?: org.json.JSONArray()
        // CAT BOT cho nhe: khong ai cuon qua 60 dong, ma danh sach cang dai thi
        // moi lan cap nhat giao dien cang giat.
        return (0 until minOf(arr.length(), MAX_WEB_RESULTS)).map { i ->
            val o = arr.getJSONObject(i)
            val mirrorsArr = o.optJSONArray("mirrors") ?: org.json.JSONArray()
            WebSearchHit(
                title = o.optString("title"),
                url = o.optString("url"),
                author = o.optString("author"),
                coverUrl = o.optString("cover_url"),
                source = o.optString("source"),
                score = o.optDouble("score", 0.0),
                exact = o.optBoolean("exact", false),
                matchLabel = o.optString("match_label"),
                mirrors = (0 until mirrorsArr.length()).map { m -> mirrorsArr.optString(m) }
                    .filter { m -> m.isNotBlank() },
            )
        }
    }

    /**
     * LAM NONG truoc khi go: mo san ket noi (DNS + TLS) toi cac nguon chinh.
     * Goi khi mo man hinh Tim kiem. Chay am tham, that bai cung khong sao.
     */
    fun prewarmSearch() {
        if (searchPrewarmed) return
        searchPrewarmed = true
        viewModelScope.launch {
            try {
                ensureReady()
                val unavailable = withContext(Dispatchers.IO) { ReadingEngine.warmUpSearch() }
                if (unavailable.isNotEmpty()) {
                    _ui.update { it.copy(webUnavailableSources = it.webUnavailableSources + unavailable) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private var searchPrewarmed = false

    fun clearWebResults() =
        _ui.update {
            it.copy(
                webResults = emptyList(), webError = null, webSearching = false,
                webOpeningUrl = null, webSearchedQuery = "",
            )
        }

    /**
     * BAM KET QUA -> NGHE NGAY CHUONG 1.
     *
     * Tu link trang gioi thieu, tu tim ra link chuong dau (nut "doc tu dau" hoac
     * chuong dau trong muc luc), mo trinh phat va PHAT LUON, khong bat nguoi dung
     * qua man hinh trung gian nao.
     *
     * Neu nguon chinh loi, tu dong thu cac nguon du phong (mirrors) cua cung bo truyen.
     */
    fun playFirstChapter(hit: WebSearchHit) {
        openFirstChapterJob?.cancel()
        _ui.update { it.copy(webOpeningUrl = hit.url, webError = null) }
        openFirstChapterJob = viewModelScope.launch {
            ensureReady()
            val candidates = (listOf(hit.url) + hit.mirrors).filter { it.isNotBlank() }.distinct()
            var lastError: String? = null
            for (storyUrl in candidates) {
                try {
                    val res = withContext(Dispatchers.IO) { JSONObject(ReadingEngine.firstChapter(storyUrl)) }
                    val chapterUrl = res.optString("first_chapter_url")
                    if (chapterUrl.isBlank()) { lastError = "Khong tim thay chuong dau."; continue }
                    val source = StoryIds.sourceLabel(storyUrl)
                    val story = Story(
                        id = StoryIds.storyId(storyUrl = storyUrl, chapterUrl = chapterUrl,
                            title = res.optString("story_title").ifBlank { hit.title }, source = source),
                        title = res.optString("story_title").ifBlank { hit.title },
                        author = res.optString("author").ifBlank { hit.author },
                        // The search result is the story-level source of truth.
                        // Chapter parsers sometimes return a site/default cover
                        // belonging to another item; only use it when the search
                        // result itself has no cover.
                        coverUrl = hit.coverUrl.ifBlank { res.optString("cover_url") },
                        totalChapters = res.optInt("total_chapters", 0),
                        tags = listOf("Tim kiem"),
                        color = DEFAULT_ACCENT,
                        currentChapter = 1,
                        source = source,
                        storyUrl = storyUrl,
                        mirrorUrls = candidates.filterNot { it == storyUrl },
                        chapterUrl = chapterUrl,
                        chapterTitle = res.optString("chapter_title"),
                    )
                    persistProgress()
                    _ui.update {
                        it.copy(
                            webOpeningUrl = null,
                            activeStory = story,
                            isPlayerOpen = true,
                            hiddenMiniPlayerStoryId = null,
                            sheet = null,
                            isKaraoke = false,
                            activeSentenceIndex = 0,
                            chapterNumber = 1,
                            chapterTitle = story.chapterTitle,
                        )
                    }
                    // autoPlay = true -> tai xong chuong 1 la doc luon.
                    playChapterUrl(chapterUrl, story)
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                }
            }
            _ui.update {
                it.copy(
                    webOpeningUrl = null,
                    webError = lastError ?: "Khong mo duoc chuong 1 cua truyen nay.",
                )
            }
        }
    }

    /** Mo 1 URL chuong va phat ngay (dung cho luong "bam ket qua la nghe"). */
    private fun playChapterUrl(chapterUrl: String, baseStory: Story) =
        loadChapter(chapterUrl, autoPlay = true, baseStory = baseStory, resumeSentence = 0, chapterNumberHint = 1)

    // ---------- sleep ----------
    private fun startSleepTimer() = viewModelScope.launch {
        while (true) {
            delay(1000)
            if (sleepSeconds > 0) {
                sleepSeconds--
                if (sleepSeconds in 1..30 && pcmActive) pcmEngine.setVolume(s.playerVolume * sleepSeconds / 30f)
                if (sleepSeconds <= 0) { pause();if(pcmActive)pcmEngine.setVolume(s.playerVolume);_ui.update { it.copy(sleepTimer=null,sleepMode=SleepMode.OFF) } }
                else _ui.update { it.copy(sleepTimer = ceil(sleepSeconds / 60.0).toInt()) }
            }
        }
    }

    // ---------- paste ----------
    fun openPaste() = _ui.update { it.copy(pasteOpen = true, paste = PasteState()) }
    fun closePaste() = _ui.update { it.copy(pasteOpen = false) }
    fun setPasteUrl(u: String) = _ui.update { it.copy(paste = it.paste.copy(url = u, clipErr = false, error = null)) }

    fun markClipboardEmpty() = _ui.update { it.copy(paste = it.paste.copy(clipErr = true)) }
    fun clearPasteResult() = _ui.update { it.copy(paste = it.paste.copy(result = null)) }

    fun analyzeLink(link: String) {
        val url = UrlPolicy.normalizeHttps(link)
        if (url == null) {
            _ui.update {
                it.copy(paste = it.paste.copy(error = "Chỉ hỗ trợ liên kết HTTPS hợp lệ"))
            }
            return
        }
        _ui.update { it.copy(paste = it.paste.copy(analyzing = true, step = 1, error = null, result = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            ensureReady()
            try {
                _ui.update { it.copy(paste = it.paste.copy(step = 1)) }
                val res = JSONObject(ReadingEngine.chapter(url))
                _ui.update { it.copy(paste = it.paste.copy(step = 2)) }
                val storyTitle = res.optString("story_title").ifBlank { res.optString("title", "Truyện") }
                val coverUrl = res.optString("cover_url", "")
                val chapterUrl = res.optString("chapter_url", url)
                val nextUrl = res.chapterLink("next_url")
                val prevUrl = res.chapterLink("prev_url")
                val chTitle = res.optString("title", "")
                val source = StoryIds.sourceLabel(url)
                val storyUrl = StoryIds.guessStoryUrlFromChapter(chapterUrl).ifBlank { null }
                val sid = StoryIds.storyId(storyUrl = storyUrl, chapterUrl = chapterUrl, title = storyTitle, source = source)
                val story = Story(
                    id = sid, title = storyTitle, author = source,
                    coverUrl = coverUrl, totalChapters = 0, tags = listOf("Nhập khẩu"),
                    color = DEFAULT_ACCENT, currentChapter = 1, source = source,
                    storyUrl = storyUrl, chapterUrl = chapterUrl, nextUrl = nextUrl, prevUrl = prevUrl,
                    chapterTitle = chTitle,
                )
                delay(120)
                _ui.update { it.copy(paste = it.paste.copy(analyzing = false, step = 3, result = PasteResult(story, source))) }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(paste = it.paste.copy(analyzing = false, error = e.message ?: "Không phân tích được link"))
                }
            }
        }
    }

    fun importEpub(uri: Uri) {
        _ui.update { it.copy(epubImport = EpubImportState(importing = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureReady()
                val result = epubImporter.import(uri)
                _ui.update {
                    it.copy(
                        epubImport = EpubImportState(
                            importing = false,
                            fileName = result.story.localFileName.orEmpty(),
                            result = result,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _ui.update {
                    it.copy(epubImport = EpubImportState(error = e.message ?: "Không nhập được EPUB"))
                }
            }
        }
    }
    fun clearEpubImport()=_ui.update{it.copy(epubImport=EpubImportState())}
    fun listenImportedEpub(){val r=s.epubImport.result?:return;clearEpubImport();openPlayer(r.story)}
    fun saveImportedEpub(openLibrary:Boolean=true){val r=s.epubImport.result?:return;saveToLibrary(r.story);clearEpubImport();if(openLibrary)setTab(Tab.LIBRARY)}

    // ---------- media metadata ----------
    private fun publishPlayback() {
        val st = s.activeStory
        val meta = PlaybackMetadata(
            storyId = st?.id ?: "",
            storyTitle = st?.title ?: "NovelBook",
            author = st?.author ?: "",
            chapterTitle = s.chapterTitle,
            chapterIndex = s.chapterNumber,
            totalChapters = st?.totalChapters ?: s.tocUrls.size,
            coverUrl = st?.coverUrl ?: "",
            source = st?.source,
            positionMs = s.positionMs,
            durationMs = s.durationMs.coerceAtLeast(1L),
            speed = s.speed,
            voiceId = s.selectedVoice,
            isPlaying = s.isPlaying,
            isBuffering = s.isBuffering,
            hasPrevious = !st?.prevUrl.isNullOrBlank() ||
                s.chapterItems.any { item -> item.index == (st?.currentChapter ?: s.chapterNumber) - 1 },
            hasNext = !st?.nextUrl.isNullOrBlank() ||
                s.chapterItems.any { item -> item.index == (st?.currentChapter ?: s.chapterNumber) + 1 },
        )
        _ui.update { it.copy(playback = meta) }
        syncPlaybackService(meta)
    }

    private fun syncPlaybackService(meta: PlaybackMetadata) {
        if (mediaSessionDismissed || s.activeStory == null) {
            runCatching { ReadingPlaybackService.stop(getApplication()) }
        } else {
            runCatching { ReadingPlaybackService.update(getApplication(), meta) }
        }
    }

    fun filteredChapterItems(): List<ChapterUiModel> {
        val all = s.chapterItems
        return when(s.chapterFilter){ChapterFilter.LISTENING->all.filter{it.isCurrent||it.status==ChapterListenStatus.IN_PROGRESS};ChapterFilter.HEARD->all.filter{it.status==ChapterListenStatus.COMPLETED};ChapterFilter.ALL->all}
    }

    /** Final small atomic snapshot; does not depend on viewModelScope staying alive. */
    private fun persistProgressNow() {
        if (s.sentences.isEmpty()) return
        val generation = Store.writeGeneration()
        val story = s.activeStory ?: return
        val url = story.chapterUrl ?: return
        val sentenceCount = s.sentences.size
        val sentenceIndex = s.activeSentenceIndex.coerceAtLeast(0)
        val progress = if (sentenceCount > 0) {
            (sentenceIndex.toFloat() / sentenceCount).coerceIn(0f, 1f)
        } else s.progress.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        val saved = story.copy(
            currentChapter = s.chapterNumber,
            chapterTitle = s.chapterTitle,
            sentenceIndex = sentenceIndex,
            sentenceCount = sentenceCount,
            progress = progress,
            positionMs = s.positionMs,
            updatedAt = now,
            lastListenedAt = now,
        )
        val chapterId = StoryIds.chapterId(url, s.chapterNumber)
        Store.withWriteGeneration(generation) {
        runCatching { ReadingEngine.historyUpsert(storyToJson(saved)) }
        runCatching { ReadingEngine.libraryUpsert(storyToJson(saved)) }
        runCatching {
            Store.upsertProgress(saved.id, buildJsonObject {
                put("chapterId", JsonPrimitive(chapterId)); put("chapterUrl", JsonPrimitive(url))
                put("chapterTitle", JsonPrimitive(s.chapterTitle)); put("chapterIndex", JsonPrimitive(s.chapterNumber))
                put("sentenceIndex", JsonPrimitive(sentenceIndex)); put("sentenceCount", JsonPrimitive(sentenceCount))
                put("progress", JsonPrimitive(progress.toDouble())); put("positionMs", JsonPrimitive(saved.positionMs))
            })
        }
        }
    }

    override fun onCleared() {
        ReadingMediaBridge.unregister(mediaCommands)
        persistProgressNow()
        mediaSessionDismissed = true
        stopPlayback()
        if (pcmActive) pcmEngine.release()
        progressJob?.cancel()
        persistJob?.cancel()
        chapterLoadJob?.cancel()
        webSearchJob?.cancel()
        autoSearchJob?.cancel()
        openFirstChapterJob?.cancel()
        super.onCleared()
    }
}
