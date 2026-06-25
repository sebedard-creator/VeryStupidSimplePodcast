package com.verystupidsimplepodcast.data.network

import com.google.gson.annotations.SerializedName

data class XdioSearchRequest(
    val queries: List<XdioSearchQuery>
)

data class XdioSearchQuery(
    val indexUid: String = "xdio-shows",
    val q: String,
    val limit: Int = 15
)

data class XdioSearchResponse(
    val results: List<XdioSearchResultIndex>
)

data class XdioSearchResultIndex(
    val indexUid: String,
    val hits: List<XdioHit>
)

data class XdioHit(
    @SerializedName("show_id") val showId: Int,
    @SerializedName("show_title") val showTitle: String,
    @SerializedName("show_url") val showUrl: String?
)

data class XdioEpisode(
    val eid: Int,
    val title: String,
    val pubDate: String,
    val audio: String,
    val duration: Long
)

data class XdioShowFeedResponse(
    val meta: Map<String, String>?,
    val items: List<XdioEpisode>
)
