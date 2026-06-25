package com.verystupidsimplepodcast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verystupidsimplepodcast.data.db.PodcastDatabase
import com.verystupidsimplepodcast.data.network.PodcastRepository
import com.verystupidsimplepodcast.data.network.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.verystupidsimplepodcast.audio.PodcastMediaSessionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import com.google.common.util.concurrent.ListenableFuture

class PodcastViewModel(application: Application) : AndroidViewModel(application) {
    private val db = PodcastDatabase.getDatabase(application)
    private val repository = PodcastRepository(db.subscriptionDao(), db.episodeDao())

    val allEpisodes = db.episodeDao().getAllEpisodesChronologically().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val subscriptions = db.subscriptionDao().getAllSubscriptions().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    init {
        // Refresh feeds on launch silently
        viewModelScope.launch {
            db.episodeDao().deleteOrphanEpisodes()
            repository.refreshAllFeeds()
        }
    }

    fun searchPodcasts(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = emptyList() // clear previous
            repository.searchPodcasts(query, _searchResults)
            _isSearching.value = false
        }
    }

    fun subscribeToPodcast(searchResult: SearchResult) {
        viewModelScope.launch {
            repository.subscribeAndFetchLatest(searchResult)
        }
    }

    fun unsubscribePodcast(subscription: com.verystupidsimplepodcast.data.db.Subscription) {
        viewModelScope.launch {
            db.subscriptionDao().deleteSubscription(subscription)
            db.episodeDao().deleteEpisodesBySubscriptionId(subscription.id)
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPlayingEpisode = MutableStateFlow<com.verystupidsimplepodcast.data.db.Episode?>(null)
    val currentPlayingEpisode: StateFlow<com.verystupidsimplepodcast.data.db.Episode?> = _currentPlayingEpisode

    fun initPlayer(context: Context) {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, PodcastMediaSessionService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                mediaController = controller
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) startProgressLoop() else stopProgressLoop()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        updateCurrentEpisodeFromMediaId(mediaItem?.mediaId)
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun updateCurrentEpisodeFromMediaId(mediaId: String?) {
        val id = mediaId?.toLongOrNull()
        if (id != null) {
            viewModelScope.launch {
                _currentPlayingEpisode.value = db.episodeDao().getEpisodeById(id)
            }
        } else {
            _currentPlayingEpisode.value = null
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && mc.isPlaying) {
                    val ep = _currentPlayingEpisode.value
                    if (ep != null) {
                        var actualDuration = ep.durationMs
                        if (mc.duration > 0 && mc.duration != androidx.media3.common.C.TIME_UNSET) {
                            actualDuration = mc.duration
                        }
                        _currentPlayingEpisode.value = ep.copy(progressMs = mc.currentPosition, durationMs = actualDuration)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }

    fun playEpisode(episode: com.verystupidsimplepodcast.data.db.Episode) {
        val mc = mediaController ?: return
        if (mc.currentMediaItem?.mediaId == episode.id.toString()) {
            if (!mc.isPlaying) mc.play()
            return
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(episode.audioUrl)
            .build()
        mc.setMediaItem(mediaItem)
        mc.seekTo(episode.progressMs)
        mc.prepare()
        mc.play()
    }

    fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun skipForward() {
        val mc = mediaController ?: return
        mc.seekTo(mc.currentPosition + 30000)
    }

    fun skipBackward() {
        val mc = mediaController ?: return
        mc.seekTo((mc.currentPosition - 10000).coerceAtLeast(0))
    }

    fun markEpisodeAsUnplayed(episodeId: Long) {
        viewModelScope.launch {
            db.episodeDao().updateProgress(episodeId, 0L, false)
        }
    }

    fun markEpisodeAsPlayed(episodeId: Long, durationMs: Long) {
        viewModelScope.launch {
            db.episodeDao().updateProgress(episodeId, durationMs, true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
