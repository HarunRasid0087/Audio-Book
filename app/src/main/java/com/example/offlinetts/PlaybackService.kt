package com.example.offlinetts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Foreground service that owns a [MediaSessionCompat] and shows a media-style
 * notification with Previous / Play-Pause / Next / Stop controls.
 *
 * It does NOT own the [TextToSpeech] engine — [MainActivity] keeps driving the
 * [TtsManager]. The service simply mirrors playback state into a lock-screen
 * notification and routes control taps back to the Activity via [controller].
 *
 * This keeps reading alive when the app is backgrounded (the foreground service
 * prevents the process from being killed while audio is playing).
 */
class PlaybackService : Service() {

    /** Callback the Activity registers to receive transport-control taps. */
    interface Controller {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSkipNext()
        fun onSkipPrevious()
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var controller: Controller? = null

    private var title: String = ""
    private var subtitle: String = ""
    private var isPlaying: Boolean = false
    private var hasContent: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "OfflineReaderSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { controller?.onPlay() }
                override fun onPause() { controller?.onPause() }
                override fun onStop() { controller?.onStop() }
                override fun onSkipToNext() { controller?.onSkipNext() }
                override fun onSkipToPrevious() { controller?.onSkipPrevious() }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Route hardware/lock-screen media buttons into the session.
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY -> controller?.onPlay()
            ACTION_PAUSE -> controller?.onPause()
            ACTION_STOP -> controller?.onStop()
            ACTION_NEXT -> controller?.onSkipNext()
            ACTION_PREV -> controller?.onSkipPrevious()
        }
        return START_NOT_STICKY
    }

    fun setController(c: Controller?) { controller = c }

    /** Push current playback state and (re)build/refresh the notification. */
    fun update(title: String, subtitle: String, isPlaying: Boolean, hasContent: Boolean) {
        this.title = title
        this.subtitle = subtitle
        this.isPlaying = isPlaying
        this.hasContent = hasContent

        updateMediaSessionState()

        if (!hasContent) {
            stopForegroundCompat(removeNotification = true)
            return
        }

        val notification = buildNotification()
        if (isPlaying) {
            startForeground(NOTIF_ID, notification)
        } else {
            // Keep the notification but allow swipe-to-dismiss when paused.
            startForeground(NOTIF_ID, notification)
            stopForegroundCompat(removeNotification = false)
        }
    }

    private fun updateMediaSessionState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
        else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )

        val playPause = if (isPlaying)
            NotificationCompat.Action(
                R.drawable.ic_pause, getString(R.string.pause),
                command(ACTION_PAUSE)
            )
        else
            NotificationCompat.Action(
                R.drawable.ic_play, getString(R.string.play),
                command(ACTION_PLAY)
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(subtitle.ifBlank { getString(R.string.notif_reading) })
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_skip_prev, getString(R.string.prev), command(ACTION_PREV))
            .addAction(playPause)
            .addAction(R.drawable.ic_skip_next, getString(R.string.next), command(ACTION_NEXT))
            .addAction(
                R.drawable.ic_stop, getString(R.string.stop),
                command(ACTION_STOP)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(command(ACTION_STOP))
            )
            .setDeleteIntent(command(ACTION_STOP))
            .build()
    }

    private fun command(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) Service.STOP_FOREGROUND_REMOVE
                else Service.STOP_FOREGROUND_DETACH
            )
        } else {
            stopForeground(removeNotification)
        }
        if (removeNotification) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        controller = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIF_ID = 1001

        const val ACTION_PLAY = "com.example.offlinetts.PLAY"
        const val ACTION_PAUSE = "com.example.offlinetts.PAUSE"
        const val ACTION_STOP = "com.example.offlinetts.STOP"
        const val ACTION_NEXT = "com.example.offlinetts.NEXT"
        const val ACTION_PREV = "com.example.offlinetts.PREV"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
