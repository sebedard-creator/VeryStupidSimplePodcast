package com.verystupidsimplepodcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verystupidsimplepodcast.data.network.SearchResult
import com.verystupidsimplepodcast.ui.PodcastViewModel

@Composable
fun SearchScreen(
    viewModel: PodcastViewModel,
    onResultClicked: (SearchResult) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { 
                query = it
                if (it.length > 2) {
                    viewModel.searchPodcasts(it)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            label = { Text("Search Podcasts") },
            singleLine = true
        )

        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results) { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResultClicked(result) }
                        .padding(16.dp)
                ) {
                    Column {
                        Text(text = result.title, style = MaterialTheme.typography.titleMedium)
                        Text(text = result.rssUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                Divider()
            }
        }
    }
}
