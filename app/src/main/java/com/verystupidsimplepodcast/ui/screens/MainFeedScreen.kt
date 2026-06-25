package com.verystupidsimplepodcast.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verystupidsimplepodcast.data.db.Episode
import com.verystupidsimplepodcast.data.db.Subscription
import com.verystupidsimplepodcast.ui.PodcastViewModel
import com.verystupidsimplepodcast.ui.components.EpisodeCard

@Composable
fun MainFeedScreen(
    viewModel: PodcastViewModel,
    onEpisodeClick: (Episode) -> Unit
) {
    val episodes by viewModel.allEpisodes.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()

    if (episodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No episodes yet. Search and subscribe!")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(episodes) { episode ->
                val subscription = subscriptions.find { it.id == episode.subscriptionId }
                val podcastName = subscription?.title ?: "Unknown Podcast"
                val isUnsubscribed = subscription == null
                
                val rssUrl = subscription?.rssUrl ?: ""
                val sourceType = when {
                    rssUrl.contains("youtube.com") -> "YTB"
                    rssUrl.contains("api.xdio.ca") || rssUrl.contains("ohdio") -> "OHD"
                    else -> "POD"
                }
                
                EpisodeCard(
                    episode = episode,
                    podcastName = podcastName,
                    sourceType = sourceType,
                    isUnsubscribed = isUnsubscribed,
                    onClick = { onEpisodeClick(episode) },
                    onResetProgress = { viewModel.markEpisodeAsUnplayed(episode.id) },
                    onMarkAsCompleted = { viewModel.markEpisodeAsPlayed(episode.id, episode.durationMs) }
                )
            }
        }
    }
}
