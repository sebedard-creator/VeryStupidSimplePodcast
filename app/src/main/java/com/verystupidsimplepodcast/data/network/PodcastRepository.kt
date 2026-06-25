package com.verystupidsimplepodcast.data.network

import com.verystupidsimplepodcast.data.db.Episode
import com.verystupidsimplepodcast.data.db.Subscription
import com.verystupidsimplepodcast.data.db.SubscriptionDao
import com.verystupidsimplepodcast.data.db.EpisodeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PodcastRepository(
    private val subscriptionDao: SubscriptionDao,
    private val episodeDao: EpisodeDao
) {
    private val xdioApi: XdioApi by lazy {
        Retrofit.Builder()
            .baseUrl(com.verystupidsimplepodcast.BuildConfig.XDIO_API_URL + "/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XdioApi::class.java)
    }

    private val appleApi: ApplePodcastApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApplePodcastApi::class.java)
    }

    suspend fun searchPodcasts(query: String, resultsFlow: MutableStateFlow<List<SearchResult>>) = coroutineScope {
        val appleDeferred = async(Dispatchers.IO) {
            try {
                val response = appleApi.searchPodcasts(query)
                response.results.mapNotNull {
                    if (it.feedUrl != null) {
                        SearchResult(
                            title = it.collectionName,
                            imageUrl = it.artworkUrl600 ?: "",
                            rssUrl = it.feedUrl
                        )
                    } else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        val yodioDeferred = async(Dispatchers.IO) {
            try {
                val token = "Bearer ${com.verystupidsimplepodcast.BuildConfig.XDIO_API_TOKEN}"
                val request = XdioSearchRequest(
                    queries = listOf(XdioSearchQuery(q = query))
                )
                val response = xdioApi.searchShows(token, request)
                val hits = response.results.firstOrNull()?.hits ?: emptyList()
                hits.map {
                    SearchResult(
                        title = it.showTitle,
                        imageUrl = "", // the search response doesn't provide images
                        rssUrl = "${com.verystupidsimplepodcast.BuildConfig.XDIO_API_URL}/v2/rss/show/${it.showId}"
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        val appleResults = appleDeferred.await()
        resultsFlow.value = resultsFlow.value + appleResults

        val yodioResults = yodioDeferred.await()
        // Deduplicate by RSS URL
        val combined = (resultsFlow.value + yodioResults).distinctBy { it.rssUrl }
            .sortedWith(compareBy(
                { 
                    val t = it.title.lowercase()
                    val q = query.lowercase()
                    when {
                        t == q -> 0
                        t.startsWith(q) -> 1
                        t.contains(q) -> 2
                        else -> 3
                    }
                },
                { it.title.length }
            ))
        resultsFlow.value = combined
    }

    suspend fun subscribeAndFetchLatest(searchResult: SearchResult) = withContext(Dispatchers.IO) {
        val existing = subscriptionDao.getSubscriptionByUrl(searchResult.rssUrl)
        if (existing != null) return@withContext

        val subscriptionId = subscriptionDao.insertSubscription(
            Subscription(
                rssUrl = searchResult.rssUrl,
                title = searchResult.title,
                imageUrl = searchResult.imageUrl
            )
        )
        fetchLatestEpisode(subscriptionId, searchResult.rssUrl)
    }

    suspend fun fetchLatestEpisode(subscriptionId: Long, rssUrl: String) = withContext(Dispatchers.IO) {
        try {
            if (rssUrl.contains("api.xdio.ca")) {
                val token = "Bearer ${com.verystupidsimplepodcast.BuildConfig.XDIO_API_TOKEN}"
                val response = xdioApi.getShowEpisodes(token, rssUrl)
                val episodes = response.items
                if (episodes.isNotEmpty()) {
                    val latest = episodes[0]
                    val guidStr = latest.eid.toString()
                    val existingEpisode = episodeDao.getEpisodeByGuid(guidStr)
                    if (existingEpisode == null && latest.audio.isNotEmpty()) {
                        val pubDate = parseDate(latest.pubDate)
                        episodeDao.insertEpisode(
                            Episode(
                                subscriptionId = subscriptionId,
                                guid = guidStr,
                                title = latest.title,
                                pubDate = pubDate,
                                durationMs = latest.duration * 1000L,
                                audioUrl = latest.audio
                            )
                        )
                    }
                }
                return@withContext
            }

            val doc = Jsoup.connect(rssUrl).get()
            val items = doc.select("item")
            if (items.isEmpty()) return@withContext

            // ONLY fetch and save index 0
            val item = items[0]
            val title = item.select("title").first()?.text() ?: "Unknown"
            val guid = item.select("guid").first()?.text() ?: item.select("link").first()?.text() ?: ""
            val pubDateStr = item.select("pubDate").first()?.text() ?: ""
            val enclosure = item.select("enclosure").first()
            val audioUrl = enclosure?.attr("url") ?: ""
            val durationStr = item.select("itunes|duration").first()?.text() ?: ""

            val pubDate = parseDate(pubDateStr)
            val durationMs = parseDuration(durationStr)

            val existingEpisode = episodeDao.getEpisodeByGuid(guid)
            if (existingEpisode == null && audioUrl.isNotEmpty()) {
                episodeDao.insertEpisode(
                    Episode(
                        subscriptionId = subscriptionId,
                        guid = guid,
                        title = title,
                        pubDate = pubDate,
                        durationMs = durationMs,
                        audioUrl = audioUrl
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun refreshAllFeeds() = withContext(Dispatchers.IO) {
        val subscriptions = subscriptionDao.getAllSubscriptionsList()
        subscriptions.forEach { sub ->
            fetchLatestEpisode(sub.id, sub.rssUrl)
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
            format.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            try {
                val format2 = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                format2.parse(dateStr)?.time ?: System.currentTimeMillis()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    private fun parseDuration(durationStr: String): Long {
        if (durationStr.isEmpty()) return 0L
        return try {
            val parts = durationStr.split(":")
            when (parts.size) {
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                1 -> parts[0].toLong() * 1000
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
