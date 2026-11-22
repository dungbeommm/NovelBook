package vn.tramtruyen.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import vn.tramtruyen.reader.model.PlaybackMetadata
import vn.tramtruyen.reader.presentation.components.CoverDefaults
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground playback owner with a real MediaSession for Android media controls.
 *
 * This is the SINGLE authoritative writer of both the MediaSession and the
 * playback notification (ReadingMediaNotifier no longer posts a competing one). It
 * also separates cheap, frequent position updates from expensive notification
 * rebuilds so the notification/cover art is not repeatedly overwritten or
 * dropped, and so playback ticking stays smooth.
 */
class ReadingPlaybackService : Service() {
    companion object {
        private val running = AtomicBoolean(false)
        private const val ACTION_UPDATE = "com.novel.reader.app.service.UPDATE"
        private const val X_TITLE = "title"
        private const val X_AUTHOR = "author"
        private const val X_CHAPTER = "chapter"
        private const val X_PLAYING = "playing"
        private const val X_POSITION = "position"
        private const val X_DURATION = "duration"
        private const val X_HAS_PREVIOUS = "hasPrevious"
        private const val X_HAS_NEXT = "hasNext"
        private const val X_STORY_ID = "storyId"
        private const val X_COVER_URL = "coverUrl"
        private const val COVER_MAX_SIZE = 512

        fun update(context: Context, meta: PlaybackMetadata) {
            val intent = Intent(context, ReadingPlaybackService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(X_TITLE, meta.storyTitle)
                .putExtra(X_AUTHOR, meta.author)
                .putExtra(X_CHAPTER, meta.chapterTitle.ifBlank { "Ch\u01b0\u01a1ng ${meta.chapterIndex}" })
                .putExtra(X_PLAYING, meta.isPlaying)
                .putExtra(X_POSITION, meta.positionMs)
                .putExtra(X_DURATION, meta.durationMs)
                .putExtra(X_HAS_PREVIOUS, meta.hasPrevious)
                .putExtra(X_HAS_NEXT, meta.hasNext)
                .putExtra(X_STORY_ID, meta.storyId)
                .putExtra(X_COVER_URL, meta.coverUrl)
            runCatching {
                if (running.get()) context.startService(intent)
                else ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                // Android 12+ can reject a foreground-service launch after the app
                // has moved to background. Do not let a notification update crash it.
                running.set(false)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadingPlaybackService::class.java))
        }
    }

    private lateinit var session: MediaSessionCompat
    private var title = "NovelBook"
    private var author = ""
    private var chapter = "\u0110ang \u0111\u1ecdc"
    private var playing = false
    private var position = 0L
    private var duration = 1L
    private var hasPrevious = false
    private var hasNext = false
    private var storyId = ""
    private var coverUrl = ""
    private var cover: Bitmap? = null
    private var lastNotificationSig = ""
    private var lastMetadataSig = ""

    // Rut tai nghe / mat ket noi bluetooth KHONG con tu dong tam dung nua.
    // Giu receiver de sau nay con cho moc vao, nhung than ham la no-op.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // No-op co chu y: chi nguoi dung moi duoc tam dung.
        }
    }

    /** Nhan nut Play/Pause/Stop/Next/Prev tu notification action (broadcast noi bo). */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ReadingMediaNotifier.ACTION_PLAY -> ReadingMediaBridge.play()
                ReadingMediaNotifier.ACTION_PAUSE -> ReadingMediaBridge.pause()
                ReadingMediaNotifier.ACTION_STOP -> ReadingMediaBridge.stop()
                ReadingMediaNotifier.ACTION_NEXT -> ReadingMediaBridge.next()
                ReadingMediaNotifier.ACTION_PREV -> ReadingMediaBridge.prev()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        createChannel()
        session = MediaSessionCompat(this, "ReaderPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // Goi THANG vao ViewModel qua ReadingMediaBridge. Khong mo Activity:
                // mo Activity tu day bi Android 10+ chan khi app o nen, nut tam dung
                // tren thanh media se "khong an".
                override fun onPlay() = ReadingMediaBridge.play()
                override fun onPause() = ReadingMediaBridge.pause()
                override fun onStop() = ReadingMediaBridge.stop()
                override fun onSkipToNext() = ReadingMediaBridge.next()
                override fun onSkipToPrevious() = ReadingMediaBridge.prev()
                override fun onSeekTo(pos: Long) {
                    ReadingMediaBridge.seekTo(pos.coerceIn(0L, duration.coerceAtLeast(1L)))
                }
            })
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Nut tren notification (va mot so OEM van goi action PI thay vi session)
        // di qua broadcast NOI BO -> bridge, cung khong mo Activity.
        val filter = IntentFilter().apply {
            addAction(ReadingMediaNotifier.ACTION_PLAY)
            addAction(ReadingMediaNotifier.ACTION_PAUSE)
            addAction(ReadingMediaNotifier.ACTION_STOP)
            addAction(ReadingMediaNotifier.ACTION_NEXT)
            addAction(ReadingMediaNotifier.ACTION_PREV)
        }
        ContextCompat.registerReceiver(
            this,
            controlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Never resurrect a playback FGS from a sticky/null restart. Android 12+
        // rejects startForeground() in that background restart and kills the app.
        if (intent?.action != ACTION_UPDATE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        title = intent.getStringExtra(X_TITLE) ?: title
        author = intent?.getStringExtra(X_AUTHOR) ?: author
        chapter = intent?.getStringExtra(X_CHAPTER) ?: chapter
        playing = intent?.getBooleanExtra(X_PLAYING, playing) ?: playing
        position = intent?.getLongExtra(X_POSITION, position) ?: position
        duration = intent?.getLongExtra(X_DURATION, duration) ?: duration
        hasPrevious = intent?.getBooleanExtra(X_HAS_PREVIOUS, hasPrevious) ?: hasPrevious
        hasNext = intent?.getBooleanExtra(X_HAS_NEXT, hasNext) ?: hasNext
        val newStoryId = intent?.getStringExtra(X_STORY_ID) ?: storyId
        val newCoverUrl = intent?.getStringExtra(X_COVER_URL) ?: coverUrl
        val coverTargetChanged = newStoryId != storyId || newCoverUrl != coverUrl
        storyId = newStoryId
        coverUrl = newCoverUrl

        // Position/duration change every ~500ms while playing. Only rebuild the
        // notification + session metadata (which can carry a Bitmap over Binder IPC)
        // when the VISIBLE content actually changes; otherwise just push the cheap
        // playback-state position so lock-screen/Bluetooth controls stay in sync.
        // This is what previously caused the cover art to flicker away: this service
        // used to rebuild the whole notification (with no art at all) on every tick.
        val notificationSig =
            "$title|$author|$chapter|$playing|$hasPrevious|$hasNext|$coverUrl"
        val metadataSig = "$title|$author|$chapter|${duration.coerceAtLeast(1L)}|$coverUrl"
        val firstStart = lastNotificationSig.isEmpty()
        val notificationChanged = notificationSig != lastNotificationSig
        val metadataChanged = metadataSig != lastMetadataSig
        lastNotificationSig = notificationSig
        lastMetadataSig = metadataSig

        if (coverTargetChanged) {
            // Xoa bia cu NGAY: neu tai bia moi loi thi thong bao khong duoc
            // giu bia cua truyen truoc duoi tieu de truyen moi.
            cover = null
            loadCover(newStoryId, newCoverUrl)
        }

        updatePlaybackState()
        // Duration is MediaSession metadata, not playback state. Keep it fresh
        // even when the notification's visible text did not change; otherwise
        // Android keeps a 1 ms/stale seek range and refuses drag-to-seek.
        if (firstStart || metadataChanged || coverTargetChanged) {
            updateMetadata()
        }
        if (firstStart || notificationChanged || coverTargetChanged) {
            try {
                startForeground(ReadingMediaNotifier.NOTIF_ID, buildNotification())
            } catch (_: RuntimeException) {
                // Includes ForegroundServiceStartNotAllowedException on API 31+.
                // Stopping is safer than crashing/restarting in a loop.
                running.set(false)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun loadCover(sid: String, url: String) {
        thread(isDaemon = true, name = "playback-cover") {
            val bmp = if (url.startsWith("http")) {
                runCatching {
                    val req = okhttp3.Request.Builder().url(url).build()
                    vn.tramtruyen.reader.engine.HttpClient.sharedClient.newCall(req).execute().use { resp ->
                        val body = resp.body ?: return@use null
                        if (body.contentLength() > 12L * 1024L * 1024L) return@use null
                        val data = body.bytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > COVER_MAX_SIZE * 2 || bounds.outHeight / sample > COVER_MAX_SIZE * 2) sample *= 2
                        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull()
            } else {
                runCatching { BitmapFactory.decodeResource(resources, CoverDefaults.forKey(sid)) }.getOrNull()
            }
            if (bmp == null) return@thread
            val w = bmp.width
            val h = bmp.height
            val scaled = if (w > COVER_MAX_SIZE || h > COVER_MAX_SIZE) {
                val scale = COVER_MAX_SIZE.toFloat() / maxOf(w, h)
                runCatching {
                    Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
                }.getOrDefault(bmp)
            } else bmp
            // Apply only if this is still the story currently showing (avoids races
            // when the user quickly skips between chapters/stories).
            if (sid == storyId && url == coverUrl) {
                cover = scaled
                updateMetadata()
                val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this@ReadingPlaybackService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (canNotify) {
                    try {
                        NotificationManagerCompat.from(this@ReadingPlaybackService)
                            .notify(ReadingMediaNotifier.NOTIF_ID, buildNotification())
                    } catch (_: SecurityException) {
                        // Permission can be revoked between the check and notify.
                    }
                }
            }
        }
    }

    /** Cheap: only pushes position/state — safe to call every ~500ms while playing. */
    private fun updatePlaybackState() {
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO
        if (hasPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (hasNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    position.coerceAtLeast(0L),
                    if (playing) 1f else 0f,
                )
                .build()
        )
    }

    /** Heavier: rebuilds full metadata (incl. cover bitmap) — only call on real content changes. */
    private fun updateMetadata() {
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, chapter)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration.coerceAtLeast(1L))
        cover?.let {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        session.setMetadata(metaBuilder.build())
    }

    /** Mo app (bam vao than notification) — van dung getActivity. */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, ReaderActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            90,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Nut dieu khien tren notification: broadcast NOI BO toi controlReceiver,
     * roi di thang vao ReadingMediaBridge. Khong mo Activity (tranh bi chan nen).
     */
    private fun controlPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Mau nhan cua thong bao. Tren Android 12+ lay accent Monet cua he thong
     * (dong bo voi hinh nen), cac ban cu hon dung mau thuong hieu.
     */
    private fun monetAccentColor(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(this, android.R.color.system_accent1_300)
        } else {
            ContextCompat.getColor(this, R.color.nr_accent)
        }
    }.getOrElse { ContextCompat.getColor(this, R.color.nr_accent) }

    private fun buildNotification(): android.app.Notification {
        val previous = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Ch\u01b0\u01a1ng tr\u01b0\u1edbc",
            controlPendingIntent(ReadingMediaNotifier.ACTION_PREV, 91),
        )
        val toggle = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "T\u1ea1m d\u1eebng",
                controlPendingIntent(ReadingMediaNotifier.ACTION_PAUSE, 92),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Ph\u00e1t",
                controlPendingIntent(ReadingMediaNotifier.ACTION_PLAY, 92),
            )
        }
        val next = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Ch\u01b0\u01a1ng sau",
            controlPendingIntent(ReadingMediaNotifier.ACTION_NEXT, 93),
        )
        val stop = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "D\u1eebng",
            controlPendingIntent(ReadingMediaNotifier.ACTION_STOP, 94),
        )
        return NotificationCompat.Builder(this, ReadingMediaNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_novelreader)
            .setColor(monetAccentColor())
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(chapter)
            .setSubText(author)
            .setLargeIcon(cover)
            .setContentIntent(openAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(previous)
            .addAction(toggle)
            .addAction(next)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    ReadingMediaNotifier.CHANNEL_ID,
                    "NovelBook \u2014 Ph\u00e1t \u00e2m thanh",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { unregisterReceiver(noisyReceiver) }
        runCatching { unregisterReceiver(controlReceiver) }
        runCatching { session.isActive = false; session.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
