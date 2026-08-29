package io.github.edwardlucas.gwamanager.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.util.Log
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.ui.WebAppActivity
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

class MediaPlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        MediaPlaybackController.play(this@MediaPlaybackService)
                    }

                    override fun onPause() {
                        MediaPlaybackController.pause(this@MediaPlaybackService)
                    }

                    override fun onStop() {
                        MediaPlaybackController.stop(this@MediaPlaybackService)
                    }

                    override fun onSeekTo(position: Long) {
                        MediaPlaybackController.seekTo(this@MediaPlaybackService, position)
                    }

                    override fun onFastForward() {
                        MediaPlaybackController.seekForward(this@MediaPlaybackService)
                    }

                    override fun onRewind() {
                        MediaPlaybackController.seekBackward(this@MediaPlaybackService)
                    }

                    override fun onSkipToNext() {
                        MediaPlaybackController.nextTrack(this@MediaPlaybackService)
                    }

                    override fun onSkipToPrevious() {
                        MediaPlaybackController.previousTrack(this@MediaPlaybackService)
                    }
                }
            )
            isActive = true
        }
        updateForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                MediaPlaybackController.play(this)
            }

            ACTION_PAUSE -> {
                MediaPlaybackController.pause(this)
            }

            ACTION_STOP -> {
                MediaPlaybackController.stop(this)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        if (!updateForeground()) {
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun updateForeground(): Boolean {
        val snapshot = MediaPlaybackController.snapshot()
        if (snapshot.webAppId == null) {
            stopSelf()
            return false
        }
        updatePlatformMediaSession(snapshot)
        return try {
            startForeground(
                NOTIFICATION_ID,
                createNotification(snapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            true
        } catch (exception: SecurityException) {
            Log.e(TAG, "Unable to promote media playback to a foreground service.", exception)
            stopSelf()
            false
        }
    }

    private fun updatePlatformMediaSession(snapshot: MediaPlaybackController.Snapshot) {
        val title = snapshot.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.media_playback_title)
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        snapshot.artist?.takeIf { it.isNotBlank() }?.let {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
        }
        snapshot.album?.takeIf { it.isNotBlank() }?.let {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it)
        }
        if (snapshot.durationMs > 0L) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs)
        }
        snapshot.artwork?.takeIf { !it.isRecycled }?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(metadataBuilder.build())
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(platformActions(snapshot.supportedFeatures))
                .setState(
                    snapshot.playbackState,
                    snapshot.positionMs,
                    snapshot.playbackRate,
                    snapshot.positionUpdateTime
                )
                .build()
        )
        snapshot.webAppId?.let { webAppId ->
            mediaSession.setSessionActivity(WebAppActivity.createPendingIntent(this, webAppId))
        }
    }

    private fun createNotification(snapshot: MediaPlaybackController.Snapshot): Notification {
        val title = snapshot.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.media_playback_title)
        val details = listOfNotNull(
            snapshot.artist?.takeIf { it.isNotBlank() },
            snapshot.album?.takeIf { it.isNotBlank() }
        ).joinToString(" - ")
        val builder = NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(details.ifBlank { getString(R.string.media_playback_active) })
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (snapshot.isPlaying) {
                    R.drawable.ic_media_pause
                } else {
                    R.drawable.ic_media_play
                },
                if (snapshot.isPlaying) {
                    getString(R.string.pause)
                } else {
                    getString(R.string.play)
                },
                commandPendingIntent(if (snapshot.isPlaying) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(
                R.drawable.ic_media_stop,
                getString(R.string.stop),
                commandPendingIntent(ACTION_STOP)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )

        snapshot.webAppId?.let { webAppId ->
            builder.setContentIntent(WebAppActivity.createPendingIntent(this, webAppId))
        }
        return builder.build()
    }

    private fun platformActions(features: Long): Long {
        var actions = 0L
        if (features and GeckoMediaSession.Feature.PLAY != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_PLAY
        }
        if (features and GeckoMediaSession.Feature.PAUSE != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
        }
        if (features and GeckoMediaSession.Feature.STOP != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_STOP
        }
        if (features and GeckoMediaSession.Feature.SEEK_TO != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        }
        if (features and GeckoMediaSession.Feature.SEEK_FORWARD != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD
        }
        if (features and GeckoMediaSession.Feature.SEEK_BACKWARD != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_REWIND
        }
        if (features and GeckoMediaSession.Feature.NEXT_TRACK != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        if (features and GeckoMediaSession.Feature.PREVIOUS_TRACK != 0L) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        return actions
    }

    private fun commandPendingIntent(action: String): PendingIntent {
        val requestCode = action.hashCode()
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            MEDIA_CHANNEL_ID,
            getString(R.string.media_playback_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.media_playback_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val MEDIA_SESSION_TAG = "GwaManagerMediaSession"
        const val MEDIA_CHANNEL_ID = "web_app_media"
        const val NOTIFICATION_ID = 2000
        const val ACTION_UPDATE = "io.github.edwardlucas.gwamanager.action.MEDIA_UPDATE"
        const val ACTION_PLAY = "io.github.edwardlucas.gwamanager.action.MEDIA_PLAY"
        const val ACTION_PAUSE = "io.github.edwardlucas.gwamanager.action.MEDIA_PAUSE"
        const val ACTION_STOP = "io.github.edwardlucas.gwamanager.action.MEDIA_STOP"
    }
}
