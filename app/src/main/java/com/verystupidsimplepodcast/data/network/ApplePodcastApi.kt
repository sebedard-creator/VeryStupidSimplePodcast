package com.verystupidsimplepodcast.data.network

import retrofit2.http.GET
import retrofit2.http.Query

interface ApplePodcastApi {
    @GET("search?entity=podcast")
    suspend fun searchPodcasts(@Query("term") query: String): AppleSearchResponse
}
