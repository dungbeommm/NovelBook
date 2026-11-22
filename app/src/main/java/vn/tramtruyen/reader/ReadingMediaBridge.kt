package vn.tramtruyen.reader

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * Cau noi truc tiep giua ReadingPlaybackService (MediaSession / nut tren thanh media)
 * va PlayerViewModel dang song.
 *
 * Vi sao can file nay:
 * Truoc day MediaSession.Callback chi goi PendingIntent.getActivity de mo
 * ReaderActivity, roi ReaderActivity moi goi pause/play. Tren Android 10+ (va siet
 * hon o 12/14), mo Activity tu Service/background thuong bi he thong CHAN IM
 * LANG — runCatching nuot mat loi — nen bam tam dung/dung o thanh media
 * lock-screen, shade, Bluetooth... khong den duoc ViewModel, am thanh van chay.
 *
 * Cau noi nay chay tren process hien tai, khong can mo Activity, nen luon an.
 */
object ReadingMediaBridge {

	interface Commands {
		fun play()
		fun pause()
		fun stop()
		fun next()
		fun prev()
		fun seekTo(positionMs: Long)
	}

	private val main = Handler(Looper.getMainLooper())
	private val ref = AtomicReference<Commands?>(null)

	fun register(commands: Commands) {
		ref.set(commands)
	}

	fun unregister(commands: Commands) {
		ref.compareAndSet(commands, null)
	}

	fun play() = dispatch { it.play() }
	fun pause() = dispatch { it.pause() }
	fun stop() = dispatch { it.stop() }
	fun next() = dispatch { it.next() }
	fun prev() = dispatch { it.prev() }
	fun seekTo(positionMs: Long) = dispatch { it.seekTo(positionMs) }

	val isRegistered: Boolean
		get() = ref.get() != null

	private inline fun dispatch(crossinline block: (Commands) -> Unit) {
		val current = ref.get() ?: return
		if (Looper.myLooper() == Looper.getMainLooper()) {
			// Doc lai ref: co the vua unregister giua cho.
			ref.get()?.let(block)
		} else {
			main.post { ref.get()?.let(block) }
		}
	}
}
