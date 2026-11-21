package vn.tramtruyen.reader.presentation

import android.media.MediaDataSource
import java.util.LinkedHashMap

/** In-memory compressed-audio source used by MediaExtractor (API 23+). */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val end = minOf(data.size.toLong(), position + size).toInt()
        val count = end - position.toInt()
        if (count <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}

/** LRU cache bounded by total bytes, not entry count. */
class LruByteCache(private val maxBytes: Long = 24L * 1024L * 1024L) {
    private val map = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(key: String): ByteArray? = map[key]
    @Synchronized fun remove(key: String) {
        bytes -= map.remove(key)?.size ?: 0
    }
    @Synchronized fun put(key: String, value: ByteArray) {
        bytes -= map.put(key, value)?.size ?: 0
        bytes += value.size
        val iterator = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= entry.value.size
            iterator.remove()
        }
    }
    @Synchronized fun clear() { map.clear(); bytes = 0L }
}

/** PCM cache dùng lại kết quả MediaCodec khi seek, đổi tốc độ hoặc quay lại câu. */
class LruPcmCache(private val maxBytes: Long = 48L * 1024L * 1024L) {
    private val map = LinkedHashMap<String, DecodedPcm>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(key: String): DecodedPcm? = map[key]
    @Synchronized fun remove(key: String) {
        bytes -= map.remove(key)?.pcm?.size ?: 0
    }
    @Synchronized fun put(key: String, value: DecodedPcm) {
        bytes -= map.put(key, value)?.pcm?.size ?: 0
        bytes += value.pcm.size
        val iterator = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= entry.value.pcm.size
            iterator.remove()
        }
    }
    @Synchronized fun clear() { map.clear(); bytes = 0L }
}

/** Pauses applied after a synthesized sentence or a long sentence fragment. */
internal object SpeechAudioPolicy {
    const val MAX_CHUNK = 280

    fun tailSilenceMs(text: String): Int = when (
        text.trimEnd().trimEnd('"', '\'', '”', '’', '»', ')', ']', '」', '』').lastOrNull()
    ) {
        '.', '!', '?', '…', '。', '！', '？' -> 24
        ',', ';', ':' -> 10
        else -> 0
    }
}

fun splitIntoChunks(text: String, firstOnly: Boolean = false): List<String> =
    SentenceSegments.split(text, SpeechAudioPolicy.MAX_CHUNK, firstOnly)

/** Estimate speech duration from character count (ms). */
fun estimateDurationMs(text: String, speed: Float): Long {
    val chars = text.length.coerceAtLeast(1)
    // ~14 chars/sec at 1.0x for Vietnamese TTS average
    val sec = chars / (14.0 * speed.coerceAtLeast(0.5f))
    return (sec * 1000).toLong().coerceAtLeast(400L)
}
