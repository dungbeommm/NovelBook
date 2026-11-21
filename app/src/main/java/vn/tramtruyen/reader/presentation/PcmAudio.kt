package vn.tramtruyen.reader.presentation

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * PCM 16-bit little-endian, interleaved - dinh dang duy nhat ma
 * [GaplessAudioEngine] nhan.
 */
class DecodedPcm(
    val pcm: ByteArray,
    val sampleRate: Int,
    val channels: Int,
) {
    val bytesPerFrame: Int get() = 2 * channels.coerceAtLeast(1)
    val frames: Int get() = if (channels > 0) pcm.size / bytesPerFrame else 0
    val durationMs: Long
        get() = if (sampleRate > 0) frames.toLong() * 1000L / sampleRate else 0L
}

/**
 * Giai ma MP3 (hoac bat ky dinh dang audio nao MediaCodec ho tro) tu bo nho ra
 * PCM tho.
 *
 * Ly do ton tai: moi doan TTS la mot file MP3 rieng. Bo giai ma MP3 luon chen
 * mot khoang lang o dau file (encoder delay, ~1105 mau) va dem thanh khoang lang
 * o cuoi (padding cho du block cuoi). Khi phat lien tiep hai file MP3 thi hai
 * khoang lang nay cong lai thanh ~26-50ms im lang - dung thu ma nguoi nghe cam
 * nhan la "khong lien mach". MediaPlayer khong the khu duoc vi no lam viec o
 * muc file.
 *
 * O muc PCM thi khu duoc: [decode] cat sach phan lang o hai dau roi noi cac doan
 * lai thanh mot dong song am lien tuc.
 */
object PcmDecoder {

    /** Nguong bien do coi la "im lang" (tren thang 16-bit, ~-33dBFS). */
    private const val SILENCE_THRESHOLD = 700

    /** Khong bao gio cat qua nhieu hon muc nay o moi dau - tranh an vao tieng noi. */
    private const val MAX_TRIM_MS = 260

    /** Giu lai mot chut lang de phu am dau/cuoi khong bi cham. */
    private const val TRIM_MARGIN_MS = 6

    /** Ramp tuyen tinh chong tieng "tach" tai diem noi. */
    private const val FADE_MS = 3

    /**
     * @param tailSilenceMs khoang nghi CHU DONG chen vao cuoi moi doan. Sau khi
     *   cat sach lang cua encoder, cac cau se dinh sat nhau den muc nghe gap gap;
     *   mot khoang nghi ngan va DEU NHAU tra lai nhip noi tu nhien. Day la khoang
     *   nghi co chu dich, khac han khoang tre ngau nhien do encoder + hand-off.
     */
    fun decode(bytes: ByteArray, tailSilenceMs: Int = 24): DecodedPcm? {
        if (bytes.isEmpty()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            val format = inputFormat ?: return null
            if (trackIndex < 0) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Yeu cau ro 16-bit. Mot so decoder tra ve float neu khong noi gi.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val out = ByteArrayOutputStream(bytes.size * 10)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            // Bao ve vong lap vo han: neu codec khong bao gio phat co
            // END_OF_STREAM (vd. getInputBuffer tra null nen EOS khong duoc
            // queue), vong lap phai tu dung sau mot so vong khong tien trien
            // va tra ve phan PCM da giai ma duoc thay vi quay mai mai.
            var stallRounds = 0

            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        if (buf == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                                progressed = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                                progressed = true
                            }
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    in 0..Int.MAX_VALUE -> {
                        progressed = true
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)
                            if (buf != null) {
                                val slice = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.get(slice)
                                buf.clear()
                                out.write(slice)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> { /* TRY_AGAIN_LATER / DEPRECATED_BUFFERS_CHANGED */ }
                }
                if (progressed) {
                    stallRounds = 0
                } else if (++stallRounds >= 300) {
                    break
                }
            }

            var pcm = out.toByteArray()
            if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0) return null
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) pcm = floatToPcm16(pcm)

            pcm = trimSilence(pcm, sampleRate, channels)
            applyFades(pcm, sampleRate, channels)
            if (tailSilenceMs > 0) pcm = appendSilence(pcm, sampleRate, channels, tailSilenceMs)

            return DecodedPcm(pcm, sampleRate, channels)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun floatToPcm16(src: ByteArray): ByteArray {
        val fb = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = ByteArray(fb.remaining() * 2)
        var o = 0
        while (fb.hasRemaining()) {
            val v = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt()
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, i: Int): Int {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun frameAmplitude(pcm: ByteArray, frame: Int, bytesPerFrame: Int, channels: Int): Int {
        var peak = 0
        val base = frame * bytesPerFrame
        for (c in 0 until channels) {
            val a = abs(sampleAt(pcm, base + c * 2))
            if (a > peak) peak = a
        }
        return peak
    }

    /** Cat khoang lang do encoder MP3 sinh ra o dau va cuoi. */
    private fun trimSilence(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bytesPerFrame = 2 * channels
        val totalFrames = pcm.size / bytesPerFrame
        if (totalFrames < 8) return pcm

        val maxTrim = (sampleRate.toLong() * MAX_TRIM_MS / 1000L).toInt()
        val margin = (sampleRate.toLong() * TRIM_MARGIN_MS / 1000L).toInt()

        var start = 0
        val startLimit = minOf(maxTrim, totalFrames)
        while (start < startLimit &&
            frameAmplitude(pcm, start, bytesPerFrame, channels) < SILENCE_THRESHOLD
        ) start++

        var end = totalFrames
        val endLimit = maxOf(start, totalFrames - maxTrim)
        while (end > endLimit &&
            frameAmplitude(pcm, end - 1, bytesPerFrame, channels) < SILENCE_THRESHOLD
        ) end--

        start = maxOf(0, start - margin)
        end = minOf(totalFrames, end + margin)

        // Neu chi con lai qua it thi coi nhu phat hien sai (doan gan nhu im lang) -
        // tra ve nguyen ban cho an toan.
        if (end - start < sampleRate / 50) return pcm
        if (start == 0 && end == totalFrames) return pcm
        return pcm.copyOfRange(start * bytesPerFrame, end * bytesPerFrame)
    }

    /**
     * Sau khi cat lang, song am co the bat dau/ket thuc o bien do lon -> tieng
     * "tach" tai diem noi. Ramp 3ms o hai dau khu tieng nay.
     */
    private fun applyFades(pcm: ByteArray, sampleRate: Int, channels: Int) {
        val bytesPerFrame = 2 * channels
        val totalFrames = pcm.size / bytesPerFrame
        val fade = (sampleRate.toLong() * FADE_MS / 1000L).toInt()
        if (fade <= 1 || totalFrames < fade * 2) return

        for (f in 0 until fade) {
            val gain = f.toFloat() / fade
            scaleFrame(pcm, f, bytesPerFrame, channels, gain)
        }
        for (f in 0 until fade) {
            val gain = f.toFloat() / fade
            scaleFrame(pcm, totalFrames - 1 - f, bytesPerFrame, channels, gain)
        }
    }

    private fun scaleFrame(
        pcm: ByteArray,
        frame: Int,
        bytesPerFrame: Int,
        channels: Int,
        gain: Float,
    ) {
        val base = frame * bytesPerFrame
        for (c in 0 until channels) {
            val i = base + c * 2
            val v = (sampleAt(pcm, i) * gain).toInt().coerceIn(-32768, 32767)
            pcm[i] = (v and 0xFF).toByte()
            pcm[i + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    private fun appendSilence(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        millis: Int,
    ): ByteArray {
        val bytesPerFrame = 2 * channels
        val frames = (sampleRate.toLong() * millis / 1000L).toInt()
        if (frames <= 0) return pcm
        val out = ByteArray(pcm.size + frames * bytesPerFrame)
        System.arraycopy(pcm, 0, out, 0, pcm.size)
        return out
    }
}
