package com.verystupidsimplepodcast.data.network

data class SearchResult(
    val title: String,
    val imageUrl: String,
    val rssUrl: String
)

data class AppleSearchResponse(
    val resultCount: Int,
    val results: List<ApplePodcast>
)

data class ApplePodcast(
    val collectionName: String,
    val artworkUrl600: String?,
    val feedUrl: String?
)
