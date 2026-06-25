package com.verystupidsimplepodcast.audio

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.verystupidsimplepodcast.data.db.PodcastDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class PodcastMediaSessionService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var heartbeatJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var currentEpisodeId: Long = -1L
    private var currentDurationMs: Long = -1L

    override fun onCreate() {
        super.onCreate()

        val cache = PlayerCacheManager.getCache(this)
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)

        // Setup AudioAttributes for Focus Management
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(audioAttributes, true) // Request audio focus automatically
            .setHandleAudioBecomingNoisy(false) // EXPLICIT REQUIREMENT: Do NOT pause on headphone disconnect
            .build()
            .apply {
                playbackParameters = playbackParameters.withSpeed(1.0f) // EXPLICIT REQUIREMENT: No speed control
                repeatMode = Player.REPEAT_MODE_OFF // EXPLICIT REQUIREMENT: No Auto-Play
            }

        mediaSession = MediaSession.Builder(this, player!!)
            .build()

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startHeartbeat()
                } else {
                    stopHeartbeat()
                    saveProgress() // Save immediately on pause
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveProgress() // Save previous state BEFORE changing ID
                currentEpisodeId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p != null) {
            if (!p.playWhenReady || p.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopHeartbeat()
        saveProgress()
        mediaSession?.run {
            player.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(15_000) // 15 seconds
                saveProgress()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
    }

    private fun saveProgress() {
        val p = player ?: return
        val currentPosition = p.currentPosition
        val totalDuration = p.duration
        val episodeId = currentEpisodeId
        
        if (episodeId == -1L || currentPosition <= 0) return

        val isCompleted = if (totalDuration > 0 && totalDuration != C.TIME_UNSET) {
            (currentPosition.toFloat() / totalDuration.toFloat()) > 0.95f
        } else false

        serviceScope.launch {
            try {
                val dao = PodcastDatabase.getDatabase(applicationContext).episodeDao()
                if (totalDuration > 0 && totalDuration != C.TIME_UNSET) {
                    dao.updateProgressAndDuration(episodeId, currentPosition, totalDuration, isCompleted)
                } else {
                    dao.updateProgress(episodeId, currentPosition, isCompleted)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
