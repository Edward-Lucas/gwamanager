package io.github.edwardlucas.gwamanager.browser

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.MediaSession
import android.media.session.PlaybackState
import kotlin.math.roundToLong

object MediaPlaybackController {
    private val defaultFeatures =
        MediaSession.Feature.PLAY or
            MediaSession.Feature.PAUSE or
            MediaSession.Feature.STOP
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeSession: MediaSession? = null
    private var activeWebAppId: String? = null
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var artwork: Bitmap? = null
    private var playbackState = PlaybackState.STATE_NONE
    private var durationMs = 0L
    private var positionMs = 0L
    private var playbackRate = 1f
    private var positionUpdateTime = 0L
    private var supportedFeatures = defaultFeatures
    private var metadataGeneration = 0L

    fun onActivated(context: Context, webAppId: String, session: MediaSession) {
        synchronized(lock) {
            activeSession = session
            activeWebAppId = webAppId
            title = null
            artist = null
            album = null
            artwork = null
            playbackState = PlaybackState.STATE_NONE
            durationMs = 0L
            positionMs = 0L
            playbackRate = 1f
            positionUpdateTime = SystemClock.elapsedRealtime()
            supportedFeatures = defaultFeatures
            metadataGeneration++
        }
        requestServiceUpdate(context)
    }

    fun onDeactivated(context: Context, session: MediaSession) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            activeSession = null
            activeWebAppId = null
            title = null
            artist = null
            album = null
            artwork = null
            playbackState = PlaybackState.STATE_NONE
            durationMs = 0L
            positionMs = 0L
            playbackRate = 1f
            positionUpdateTime = 0L
            supportedFeatures = defaultFeatures
            metadataGeneration++
        }
        stopService(context)
    }

    fun onMetadata(
        context: Context,
        session: MediaSession,
        metadata: MediaSession.Metadata
    ): Long {
        val generation = synchronized(lock) {
            if (activeSession !== session) {
                return@synchronized INVALID_METADATA_GENERATION
            }
            title = metadata.title
            artist = metadata.artist
            album = metadata.album
            artwork = null
            metadataGeneration++
            metadataGeneration
        }
        if (generation == INVALID_METADATA_GENERATION) {
            return generation
        }
        requestServiceUpdate(context)
        return generation
    }

    fun onArtwork(
        context: Context,
        session: MediaSession,
        generation: Long,
        bitmap: Bitmap
    ) {
        synchronized(lock) {
            if (activeSession !== session || metadataGeneration != generation) {
                return
            }
            artwork = bitmap
        }
        requestServiceUpdate(context)
    }

    fun onFeatures(context: Context, session: MediaSession, features: Long) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            supportedFeatures = features
        }
        requestServiceUpdate(context)
    }

    fun onPositionState(
        context: Context,
        session: MediaSession,
        state: MediaSession.PositionState
    ) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            durationMs = secondsToMillis(state.duration)
            positionMs = secondsToMillis(state.position).coerceAtMost(
                durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
            )
            playbackRate = state.playbackRate
                .takeIf { it.isFinite() && it != 0.0 }
                ?.coerceIn(-4.0, 4.0)
                ?.toFloat()
                ?: 1f
            positionUpdateTime = SystemClock.elapsedRealtime()
        }
        requestServiceUpdate(context)
    }

    fun onPlay(context: Context, session: MediaSession) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            playbackState = PlaybackState.STATE_PLAYING
            positionUpdateTime = SystemClock.elapsedRealtime()
        }
        requestServiceUpdate(context)
    }

    fun onPause(context: Context, session: MediaSession) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            playbackState = PlaybackState.STATE_PAUSED
            positionUpdateTime = SystemClock.elapsedRealtime()
        }
        requestServiceUpdate(context)
    }

    fun onStop(context: Context, session: MediaSession) {
        synchronized(lock) {
            if (activeSession !== session) {
                return
            }
            playbackState = PlaybackState.STATE_STOPPED
            positionUpdateTime = SystemClock.elapsedRealtime()
        }
        stopService(context)
    }

    fun play(context: Context) {
        val session = synchronized(lock) {
            activeSession?.also {
                playbackState = PlaybackState.STATE_PLAYING
                positionUpdateTime = SystemClock.elapsedRealtime()
            }
        }
        session?.play()
        if (session != null) {
            requestServiceUpdate(context)
        }
    }

    fun pause(context: Context) {
        val session = synchronized(lock) {
            activeSession?.also {
                playbackState = PlaybackState.STATE_PAUSED
                positionUpdateTime = SystemClock.elapsedRealtime()
            }
        }
        session?.pause()
        if (session != null) {
            requestServiceUpdate(context)
        }
    }

    fun stop(context: Context) {
        val session = synchronized(lock) {
            activeSession?.also {
                playbackState = PlaybackState.STATE_STOPPED
                positionUpdateTime = SystemClock.elapsedRealtime()
            }
        }
        session?.stop()
        stopService(context)
    }

    fun seekTo(context: Context, positionMs: Long) {
        synchronized(lock) {
            activeSession?.seekTo(positionMs.coerceAtLeast(0L) / 1000.0, false)
        }
        requestServiceUpdate(context)
    }

    fun seekForward(context: Context) {
        synchronized(lock) {
            activeSession?.seekForward()
        }
        requestServiceUpdate(context)
    }

    fun seekBackward(context: Context) {
        synchronized(lock) {
            activeSession?.seekBackward()
        }
        requestServiceUpdate(context)
    }

    fun nextTrack(context: Context) {
        synchronized(lock) {
            activeSession?.nextTrack()
        }
        requestServiceUpdate(context)
    }

    fun previousTrack(context: Context) {
        synchronized(lock) {
            activeSession?.previousTrack()
        }
        requestServiceUpdate(context)
    }

    fun refresh(context: Context) {
        val hasActiveSession = synchronized(lock) {
            activeSession != null
        }
        if (hasActiveSession) {
            requestServiceUpdate(context)
        }
    }

    fun snapshot(): Snapshot {
        return synchronized(lock) {
            Snapshot(
                webAppId = activeWebAppId,
                title = title,
                artist = artist,
                album = album,
                artwork = artwork,
                playbackState = playbackState,
                durationMs = durationMs,
                positionMs = positionMs,
                playbackRate = playbackRate,
                positionUpdateTime = positionUpdateTime,
                supportedFeatures = supportedFeatures
            )
        }
    }

    private fun requestServiceUpdate(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            val shouldRunService = synchronized(lock) {
                activeSession != null && playbackState != PlaybackState.STATE_STOPPED
            }
            if (!shouldRunService) {
                return@post
            }
            try {
                val intent = Intent(appContext, MediaPlaybackService::class.java)
                    .setAction(MediaPlaybackService.ACTION_UPDATE)
                ContextCompat.startForegroundService(appContext, intent)
            } catch (exception: SecurityException) {
                Log.e(TAG, "Unable to start the media foreground service.", exception)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Media foreground service start was not allowed.", exception)
            }
        }
    }

    private fun stopService(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            appContext.stopService(Intent(appContext, MediaPlaybackService::class.java))
        }
    }

    data class Snapshot(
        val webAppId: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?,
        val playbackState: Int,
        val durationMs: Long,
        val positionMs: Long,
        val playbackRate: Float,
        val positionUpdateTime: Long,
        val supportedFeatures: Long
    ) {
        val isPlaying: Boolean
            get() = playbackState == PlaybackState.STATE_PLAYING
    }

    private fun secondsToMillis(seconds: Double): Long {
        if (!seconds.isFinite() || seconds <= 0.0) {
            return 0L
        }
        return (seconds * 1000.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .roundToLong()
    }

    private const val TAG = "MediaPlaybackController"
    private const val INVALID_METADATA_GENERATION = -1L
}
