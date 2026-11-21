package vn.tramtruyen.reader.presentation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue

/**
 * MOT AudioTrack duy nhat, khong bao gio dung giua cac doan.
 *
 * Vi sao phai thay MediaPlayer:
 * - MediaPlayer lam viec o muc FILE. Moi doan TTS la mot MP3 rieng, nen giua hai
 *   doan luon co encoder delay + padding cua MP3 (~26-50ms im lang) cong voi thoi
 *   gian ban giao. Khong the khu o muc file.
 * - Muon lien mach that thi phai lam viec o muc SONG AM: giai ma tung doan ra PCM,
 *   cat sach lang hai dau (xem [PcmDecoder]), roi GHI NOI TIEP vao cung mot
 *   AudioTrack. Voi AudioTrack thi "chuyen doan" khong con la mot su kien - no chi
 *   la byte tiep theo trong buffer. Khong the co khoang tre.
 * - Them mot loi the quyet dinh: doi toc do bang [AudioTrack.setPlaybackParams]
 *   lam duoc NGAY GIUA DONG PHAT, khong gian doan. Day la thu MediaPlayer khong
 *   lam duoc (setPlaybackParams tren player da prepare nhung chua start co the tu
 *   kich hoat phat), va cung la ly do truoc day chi 1.0x moi lien mach duoc.
 *
 * Luong du lieu: VM giai ma o IO roi goi [submit]. Mot writer thread rieng (uu
 * tien THREAD_PRIORITY_AUDIO) rut tu hang doi va ghi vao track bang write khong
 * chan, nen pause/flush luon phan hoi tuc thi.
 */
class GaplessAudioEngine(
    private val onChunkStarted: (Int) -> Unit,
    private val onDrained: (Int) -> Unit,
    private val onFatal: (String) -> Unit,
) {

    private class Item(
        val index: Int,
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    private companion object {
        /** Buffer track. Lon = chong underrun tot; nho = highlight cau bam sat hon. */
        const val BUFFER_MS = 400
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Item>()

    @Volatile private var track: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 0

    @Volatile private var running = true
    @Volatile private var paused = true
    @Volatile private var released = false

    /**
     * Tang moi lan reset. Writer thread va cac callback tre deu kiem tra gia tri
     * nay de tu huy khi phien phat da bi thay the.
     */
    @Volatile private var generation = 0

    @Volatile private var speed = 1.0f
    @Volatile private var volume = 1.0f

    // ---- vi tri phat (chi writer thread ghi framesWritten/headAccum) ----
    private var framesWritten = 0L
    private var headAccum = 0L
    private var lastHeadRaw = 0

    // ---- bookkeeping cho withinChunkMs(), do bang dong ho treo tuong ----
    @Volatile private var curIndex = -1
    @Volatile private var curStartedAt = 0L
    @Volatile private var curPausedAccum = 0L
    @Volatile private var pausedAt = 0L
    @Volatile private var writingIndex = -1
    @Volatile private var lastWrittenIndex = -1

    private val writer = Thread({ writeLoop() }, "nr-pcm-writer").apply {
        isDaemon = true
        start()
    }

    // ---------------- API cho ViewModel ----------------

    fun submit(index: Int, decoded: DecodedPcm) {
        if (released) return
        if (decoded.pcm.isEmpty()) return
        queue.offer(Item(index, decoded.pcm, decoded.sampleRate, decoded.channels))
    }

    fun queued(): Int = queue.size

    fun hasAudio(): Boolean = curIndex >= 0

    fun currentIndex(): Int = curIndex

    fun isRendering(): Boolean = !paused && curIndex >= 0 && track != null

    /** Thoi gian da phat trong doan hien tai (ms, da tinh ca toc do). */
    fun withinChunkMs(): Long {
        val started = curStartedAt
        if (started == 0L) return 0L
        val now = if (pausedAt != 0L) pausedAt else SystemClock.elapsedRealtime()
        return (now - started - curPausedAccum).coerceAtLeast(0L)
    }

    fun play() {
        if (released) return
        if (pausedAt != 0L) {
            curPausedAccum += SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0L
        }
        paused = false
        try { track?.play() } catch (_: Exception) {}
    }

    fun pause() {
        if (released) return
        if (!paused && pausedAt == 0L) pausedAt = SystemClock.elapsedRealtime()
        paused = true
        // pause() (khong phai stop()) giu nguyen du lieu con trong buffer, nen
        // resume tiep tuc dung cho da dung - khong mat mot mau nao.
        try { track?.pause() } catch (_: Exception) {}
    }

    /** Xoa sach hang doi + buffer track. Dung khi seek / doi giong / doi chuong. */
    fun reset() {
        if (released) return
        generation++
        queue.clear()
        paused = true
        curIndex = -1
        curStartedAt = 0L
        curPausedAccum = 0L
        pausedAt = 0L
        writingIndex = -1
        lastWrittenIndex = -1
        val t = track
        if (t != null) {
            try { t.pause() } catch (_: Exception) {}
            try { t.flush() } catch (_: Exception) {}
        }
        framesWritten = 0L
        headAccum = 0L
        lastHeadRaw = 0
    }

    fun setSpeed(v: Float) {
        val clamped = v.coerceIn(MIN_SPEED, MAX_SPEED)
        speed = clamped
        applyParams()
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { track?.setVolume(volume) } catch (_: Exception) {}
    }

    fun release() {
        released = true
        running = false
        generation++
        queue.clear()
        try { writer.interrupt() } catch (_: Exception) {}
        val t = track
        track = null
        if (t != null) {
            try { t.pause() } catch (_: Exception) {}
            try { t.flush() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    // ---------------- noi bo ----------------

    private fun applyParams() {
        val t = track ?: return
        try {
            t.playbackParams = PlaybackParams()
                .setSpeed(speed)
                .setPitch(1.0f)
        } catch (_: Exception) {}
    }

    private fun ensureTrack(item: Item): AudioTrack? {
        val existing = track
        if (existing != null &&
            trackSampleRate == item.sampleRate &&
            trackChannels == item.channels
        ) return existing

        // Doi dinh dang giua chuong (rat it khi - chi khi doi nha cung cap TTS).
        // Phai cho buffer hien tai phat het roi moi thay track, neu khong se cat
        // ngang tieng dang doc.
        if (existing != null) {
            val gen = generation
            var guard = 0
            while (running && gen == generation && guard < 400) {
                if (framesPending(existing) <= 0L) break
                try { Thread.sleep(20) } catch (_: InterruptedException) { return null }
                guard++
            }
            try { existing.pause() } catch (_: Exception) {}
            try { existing.flush() } catch (_: Exception) {}
            try { existing.release() } catch (_: Exception) {}
            track = null
        }

        val channelMask = when (item.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val bytesPerFrame = 2 * item.channels
        val minBuf = AudioTrack.getMinBufferSize(
            item.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val wanted = (item.sampleRate.toLong() * BUFFER_MS / 1000L).toInt() * bytesPerFrame
        val bufSize = maxOf(if (minBuf > 0) minBuf else 0, wanted).coerceAtLeast(bytesPerFrame * 512)

        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(item.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufSize)
                .build()
            trackSampleRate = item.sampleRate
            trackChannels = item.channels
            framesWritten = 0L
            headAccum = 0L
            lastHeadRaw = 0
            track = t
            try { t.setVolume(volume) } catch (_: Exception) {}
            applyParams()
            if (!paused) {
                try { t.play() } catch (_: Exception) {}
            }
            t
        } catch (e: Exception) {
            main.post { onFatal(e.message ?: "AudioTrack init failed") }
            null
        }
    }

    /**
     * So frame da ghi nhung chua phat. [AudioTrack.getPlaybackHeadPosition] tra ve
     * Int co the tran vong, nen tich luy theo delta.
     */
    private fun framesPending(t: AudioTrack): Long {
        val raw = try { t.playbackHeadPosition } catch (_: Exception) { return 0L }
        val delta = raw - lastHeadRaw
        if (delta > 0) headAccum += delta.toLong()
        lastHeadRaw = raw
        return (framesWritten - headAccum).coerceAtLeast(0L)
    }

    private fun pendingMs(pendingFrames: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        val ms = pendingFrames * 1000L / sampleRate
        return (ms / speed.coerceAtLeast(MIN_SPEED)).toLong().coerceIn(0L, 4_000L)
    }

    private fun writeLoop() {
        try {
            // URGENT_AUDIO (-19) thay vi AUDIO (-16): khi giao dien dang chay
            // 120Hz, luong ghi PCM phai thang duoc cuoc tranh CPU voi luong ve,
            // neu khong se ro/vap tieng dung luc cuon danh sach.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        while (running) {
            // Lay mau generation TRUOC khi poll: lay sau poll thi mot reset
            // chen vao giua se lam doan cua phien cu lot qua kiem tra duoi
            // nhan phien moi.
            val gen = generation
            val item = try {
                queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            if (!running || gen != generation) continue
            writingIndex = item.index
            val t = ensureTrack(item)
            if (t == null) {
                writingIndex = -1
                continue
            }
            val bytesPerFrame = 2 * item.channels

            // So frame dang cho phat TRUOC khi ghi doan nay = do tre giua luc ghi
            // va luc nguoi nghe thuc su nghe thay doan nay bat dau.
            val leadFrames = framesPending(t)
            var announced = false
            var offset = 0

            while (offset < item.pcm.size && running && gen == generation) {
                if (paused) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                val written = try {
                    t.write(
                        item.pcm, offset, item.pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING
                    )
                } catch (_: Exception) {
                    -1
                }
                if (written < 0) {
                    main.post { onFatal("AudioTrack.write $written") }
                    break
                }
                if (written == 0) {
                    // Buffer day - dung cach chinh xac de dieu tiet nhip ghi.
                    try { Thread.sleep(8) } catch (_: InterruptedException) { break }
                    continue
                }
                framesWritten += written / bytesPerFrame
                offset += written

                if (!announced) {
                    announced = true
                    scheduleStarted(item.index, gen, pendingMs(leadFrames, item.sampleRate))
                }
            }

            if (running && gen == generation && offset >= item.pcm.size) {
                lastWrittenIndex = item.index
                writingIndex = -1
            }
            if (running && gen == generation && offset >= item.pcm.size && queue.isEmpty()) {
                scheduleDrainCheck(item.index, gen, pendingMs(framesPending(t), item.sampleRate))
            } else if (writingIndex == item.index) {
                writingIndex = -1
            }
        }
    }

    /**
     * Bao "doan da bat dau" DUNG luc nguoi nghe nghe thay, khong phai luc ghi vao
     * buffer - neu khong thi cau duoc to sang se chay truoc tieng doc ca 400ms.
     */
    private fun scheduleStarted(index: Int, gen: Int, delayMs: Long) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (released || gen != generation) return
                // Pause dap vao dung cua so tre nay: hoan callback lai thay
                // vi ban khi dang pause (VM se lat giao dien ve "dang phat"
                // trong khi engine that su dang dung).
                if (paused) {
                    main.postDelayed(this, 50L)
                    return
                }
                curIndex = index
                curStartedAt = SystemClock.elapsedRealtime()
                curPausedAccum = 0L
                pausedAt = 0L
                onChunkStarted(index)
            }
        }, delayMs)
    }

    private fun scheduleDrainCheck(index: Int, gen: Int, delayMs: Long) {
        main.postDelayed({
            if (released || gen != generation) return@postDelayed
            // Neu trong luc cho ma VM da nap them doan thi khong phai het - bo qua.
            if (queue.isNotEmpty()) return@postDelayed
            // Queue có thể rỗng vì writer đã lấy đoạn kế tiếp ra để ghi. Callback
            // cũ không được báo drained giữa lúc đoạn mới đang nằm trong AudioTrack.
            if (lastWrittenIndex != index || (writingIndex >= 0 && writingIndex != index)) {
                return@postDelayed
            }
            onDrained(index)
        }, delayMs + 60L)
    }
}
