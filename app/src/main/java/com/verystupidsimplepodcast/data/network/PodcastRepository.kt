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

    suspend fun fetchLatestEpisode(subscriptionId: Long, rssUrl: String): Episode? = withContext(Dispatchers.IO) {
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
                        val newEpisode = Episode(
                                subscriptionId = subscriptionId,
                                guid = guidStr,
                                title = latest.title,
                                pubDate = pubDate,
                                durationMs = latest.duration * 1000L,
                                audioUrl = latest.audio
                            )
                        episodeDao.insertEpisode(newEpisode)
                        return@withContext newEpisode
                    }
                }
                return@withContext null
            }

            val isYouTube = rssUrl.contains("youtube.com")
            val doc = if (isYouTube) {
                Jsoup.connect(rssUrl).parser(org.jsoup.parser.Parser.xmlParser()).get()
            } else {
                Jsoup.connect(rssUrl).get()
            }
            val items = doc.select(if (isYouTube) "entry" else "item")
            if (items.isEmpty()) return@withContext null

            var validItem: org.jsoup.nodes.Element? = null
            
            var videoId = ""
            for (item in items) {
                if (isYouTube) {
                    videoId = item.getElementsByTag("yt:videoId").first()?.text() ?: ""
                    if (videoId.isNotEmpty() && isYouTubeShort(videoId)) {
                        continue // Ignore this short
                    }
                }
                validItem = item
                break
            }
            
            if (validItem == null) return@withContext null

            val title = validItem.select("title").first()?.text() ?: "Unknown"
            val guid = if (isYouTube) videoId else validItem.select("guid").first()?.text() ?: validItem.select("link").first()?.text() ?: ""
            val pubDateStr = if (isYouTube) validItem.select("published").first()?.text() ?: "" else validItem.select("pubDate").first()?.text() ?: ""
            val enclosure = validItem.select("enclosure").first()
            val audioUrl = if (isYouTube) "https://www.youtube.com/watch?v=$videoId" else enclosure?.attr("url") ?: ""
            val durationStr = validItem.select("itunes|duration").first()?.text() ?: ""

            val pubDate = parseDate(pubDateStr)
            val durationMs = parseDuration(durationStr)

            val existingEpisode = episodeDao.getEpisodeByGuid(guid)
            if (existingEpisode == null && audioUrl.isNotEmpty()) {
                val newEpisode = Episode(
                        subscriptionId = subscriptionId,
                        guid = guid,
                        title = title,
                        pubDate = pubDate,
                        durationMs = durationMs,
                        audioUrl = audioUrl
                    )
                episodeDao.insertEpisode(newEpisode)
                return@withContext newEpisode
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun refreshAllFeeds(): List<Episode> = withContext(Dispatchers.IO) {
        val subscriptions = subscriptionDao.getAllSubscriptionsList()
        val newEpisodes = mutableListOf<Episode>()
        subscriptions.forEach { sub ->
            val newEp = fetchLatestEpisode(sub.id, sub.rssUrl)
            if (newEp != null) {
                newEpisodes.add(newEp)
            }
        }
        return@withContext newEpisodes
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

    suspend fun resolveYouTubeChannel(input: String): SearchResult? = withContext(Dispatchers.IO) {
        try {
            val url = if (input.startsWith("@")) "https://www.youtube.com/$input" else input
            val service = org.schabi.newpipe.extractor.ServiceList.YouTube
            val extractor = service.getChannelExtractor(url)
            extractor.fetchPage()
            
            val title = extractor.name
            val imageUrl = "" // Fallback for now to avoid compilation issues
            val channelId = extractor.id
            val rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
            
            return@withContext SearchResult(
                title = title,
                imageUrl = imageUrl,
                rssUrl = rssUrl
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun isYouTubeShort(videoId: String): Boolean {
        try {
            val url = java.net.URL("https://www.youtube.com/shorts/$videoId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            return conn.responseCode == 200
        } catch (e: Exception) {
            return false
        }
    }
}
