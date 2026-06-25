package com.verystupidsimplepodcast.data.network

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface XdioApi {
    @POST("v2/search/multi-search")
    suspend fun searchShows(
        @Header("Authorization") authHeader: String,
        @Body request: XdioSearchRequest
    ): XdioSearchResponse

    @retrofit2.http.GET
    suspend fun getShowEpisodes(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Url url: String
    ): XdioShowFeedResponse
}
