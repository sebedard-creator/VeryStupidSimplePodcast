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
import android.widget.Toast

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

    private val _isResolvingYouTube = MutableStateFlow(false)
    val isResolvingYouTube: StateFlow<Boolean> = _isResolvingYouTube

    private val _isExtractingAudio = MutableStateFlow(false)
    val isExtractingAudio: StateFlow<Boolean> = _isExtractingAudio

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

    fun resolveAndAddYouTube(urlOrHandle: String, onResult: (SearchResult?) -> Unit) {
        viewModelScope.launch {
            _isResolvingYouTube.value = true
            val result = repository.resolveYouTubeChannel(urlOrHandle)
            _isResolvingYouTube.value = false
            onResult(result)
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

    fun initPlayer() {
        if (controllerFuture != null) return
        val appContext = getApplication<Application>()
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PodcastMediaSessionService::class.java))
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
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
        }, ContextCompat.getMainExecutor(appContext))
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

        val isYouTube = episode.audioUrl.contains("youtube.com") || episode.audioUrl.contains("youtu.be")
        
        if (isYouTube) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                _isExtractingAudio.value = true
                try {
                    val service = org.schabi.newpipe.extractor.ServiceList.YouTube
                    val extractor = service.getStreamExtractor(episode.audioUrl)
                    extractor.fetchPage()
                    
                    val audioStreams = extractor.audioStreams
                    val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }
                    
                    if (bestAudio != null) {
                        val directUrl = bestAudio.content
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            startExoPlayer(episode, directUrl)
                            _isExtractingAudio.value = false
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _isExtractingAudio.value = false
                            Toast.makeText(getApplication(), "Impossible d'extraire l'audio", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _isExtractingAudio.value = false
                        Toast.makeText(getApplication(), "Erreur YouTube: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            startExoPlayer(episode, episode.audioUrl)
        }
    }

    private fun startExoPlayer(episode: com.verystupidsimplepodcast.data.db.Episode, url: String) {
        val mc = mediaController ?: return
        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(url)
            .build()
        mc.setMediaItem(mediaItem, episode.progressMs)
        mc.prepare()
        mc.play()
    }

    fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun skipForward() {
        val mc = mediaController ?: return
        mc.seekTo(mc.currentPosition + 10000)
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
        mediaController = null
        controllerFuture = null
    }
}
