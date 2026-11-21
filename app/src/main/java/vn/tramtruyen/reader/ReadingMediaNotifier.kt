package vn.tramtruyen.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import vn.tramtruyen.reader.model.PlaybackMetadata

/**
 * Legacy media-notification helper.
 *
 * ReadingPlaybackService is now the SINGLE owner of both the MediaSession and the
 * playback notification. Previously this class posted its OWN competing
 * notification/session (same notification id) in parallel with
 * ReadingPlaybackService, and ReadingPlaybackService's notification never carried cover
 * art — so the two writers raced and the cover image would appear briefly
 * then get overwritten by the art-less one on the very next tick ("g\u1eedi
 * \u1ea3nh nhi\u1ec1u l\u1ea7n kh\u00f4ng nh\u1eadn"). That race is why this class's
 * instance methods are now safe no-ops: they exist only so existing call
 * sites in ReaderActivity keep compiling. The shared channel/action constants
 * are still used by ReadingPlaybackService.
 */
class ReadingMediaNotifier(private val activity: ReaderActivity) {

    companion object {
        const val CHANNEL_ID = "novelreader_media"
        const val NOTIF_ID = 42
        const val ACTION_PLAY = "com.novel.reader.app.PLAY"
        const val ACTION_PAUSE = "com.novel.reader.app.PAUSE"
        const val ACTION_STOP = "com.novel.reader.app.STOP"
        const val ACTION_NEXT = "com.novel.reader.app.NEXT"
        const val ACTION_PREV = "com.novel.reader.app.PREV"
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Trạm Truyện \u2014 Ph\u00e1t \u00e2m thanh",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "\u0110i\u1ec1u khi\u1ec3n \u0111\u1ecdc truy\u1ec7n b\u1eb1ng gi\u1ecdng n\u00f3i"
                    setShowBadge(false)
                }
            )
        }
    }

    // No-op: kept for call-site compatibility. ReadingPlaybackService owns playback state now.
    fun setPlayback(isPlaying: Boolean) {}

    // No-op: kept for call-site compatibility. ReadingPlaybackService owns the notification/session now.
    fun updateFrom(meta: PlaybackMetadata) {}

    // No-op: legacy signature kept for compatibility.
    fun updateMeta(
        story: String,
        chapter: String,
        coverUrl: String,
        position: Int,
        duration: Int,
        isPlaying: Boolean,
    ) {}

    // No-op: ReadingPlaybackService.stop() removes the shared notification.
    fun cancel() {}

    fun release() {}
}
