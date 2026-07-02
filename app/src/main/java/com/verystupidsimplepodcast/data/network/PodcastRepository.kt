package com.verystupidsimplepodcast.data.network

import com.verystupidsimplepodcast.data.db.Episode
import com.verystupidsimplepodcast.data.db.Subscription
import com.verystupidsimplepodcast.data.db.SubscriptionDao
import com.verystupidsimplepodcast.data.db.EpisodeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    suspend fun fetchLatestEpisode(subscriptionId: Long, rssUrl: String): List<Episode> = withContext(Dispatchers.IO) {
        val newEpisodes = mutableListOf<Episode>()
        try {
            if (rssUrl.contains("api.xdio.ca")) {
                val token = "Bearer ${com.verystupidsimplepodcast.BuildConfig.XDIO_API_TOKEN}"
                val response = xdioApi.getShowEpisodes(token, rssUrl)
                val episodes = response.items
                for (latest in episodes) {
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
                        newEpisodes.add(newEpisode)
                    } else if (existingEpisode != null) {
                        break // We found an episode already in the database, stop parsing older ones
                    }
                }
                return@withContext newEpisodes
            }

            val isYouTube = rssUrl.contains("youtube.com")
            val doc = if (isYouTube) {
                Jsoup.connect(rssUrl).parser(org.jsoup.parser.Parser.xmlParser()).get()
            } else {
                Jsoup.connect(rssUrl).get()
            }
            val items = doc.select(if (isYouTube) "entry" else "item")
            if (items.isEmpty()) return@withContext emptyList()

            // Limit scan to latest 15 items to prevent excessive network hit or BDD load
            val itemsToScan = items.take(15)

            var consecutiveKnown = 0
            for (item in itemsToScan) {
                var videoId = ""
                val guid: String
                if (isYouTube) {
                    videoId = item.getElementsByTag("yt:videoId").first()?.text() ?: ""
                    guid = videoId

                    // Point #4 fix: check DB BEFORE costly network calls
                    if (guid.isNotEmpty()) {
                        val existingEpisode = episodeDao.getEpisodeByGuid(guid)
                        if (existingEpisode != null) {
                            consecutiveKnown++
                            if (consecutiveKnown >= 3) break
                            continue
                        }
                        consecutiveKnown = 0
                    }

                    val title = item.select("title").first()?.text() ?: ""
                    if (videoId.isNotEmpty()) {
                        if (isYouTubeShort(videoId, title) || isYouTubeLive(videoId)) {
                            continue // Ignore this short or live/upcoming stream
                        }
                    }
                } else {
                    guid = item.select("guid").first()?.text() ?: item.select("link").first()?.text() ?: ""
                }

                val title = item.select("title").first()?.text() ?: "Unknown"
                val pubDateStr = if (isYouTube) item.select("published").first()?.text() ?: "" else item.select("pubDate").first()?.text() ?: ""
                val enclosure = item.select("enclosure").first()
                val audioUrl = if (isYouTube) "https://www.youtube.com/watch?v=$videoId" else enclosure?.attr("url") ?: ""
                val durationStr = item.select("itunes|duration").first()?.text() ?: ""

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
                    newEpisodes.add(newEpisode)
                    consecutiveKnown = 0
                } else if (existingEpisode != null) {
                    // Point #5 fix: tolerate gaps from filtered Shorts/Lives
                    consecutiveKnown++
                    if (consecutiveKnown >= 3) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext newEpisodes
    }

    suspend fun refreshAllFeeds(): List<Episode> = withContext(Dispatchers.IO) {
        val subscriptions = subscriptionDao.getAllSubscriptionsList()
        val deferreds = subscriptions.map { sub ->
            async {
                try {
                    fetchLatestEpisode(sub.id, sub.rssUrl)
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        }
        return@withContext deferreds.awaitAll().flatten()
    }

    private fun parseDate(dateStr: String): Long {
        // Point #8 fix: try ISO 8601 first (YouTube RSS), then RFC 2822 (classic RSS)
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),  // ISO 8601 with colon offset
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),    // ISO 8601 without colon
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US), // RFC 2822 (podcasts)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)       // Fallback
        )
        for (format in formats) {
            try {
                val date = format.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) { }
        }
        return System.currentTimeMillis()
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

    private fun isYouTubeShort(videoId: String, title: String): Boolean {
        // Optimisation: check regex on title to avoid network request
        if (title.contains("#Shorts", ignoreCase = true) || title.contains("#short", ignoreCase = true)) {
            return true
        }
        return isYouTubeShort(videoId)
    }

    private fun isYouTubeShort(videoId: String): Boolean {
        try {
            val url = java.net.URL("https://www.youtube.com/shorts/$videoId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Cookie", "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+438")
            return conn.responseCode == 200
        } catch (e: Exception) {
            return false
        }
    }

    private fun isYouTubeLive(videoId: String): Boolean {
        try {
            val url = java.net.URL("https://www.youtube.com/watch?v=$videoId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Cookie", "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+438")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            
            conn.inputStream.use { stream ->
                val reader = java.io.BufferedReader(java.io.InputStreamReader(stream, "UTF-8"))
                val builder = StringBuilder()
                val buffer = CharArray(4096)
                // Read up to 2M chars of the page to find the live tags (VOD tags often appear after 600k chars)
                while (builder.length < 2_000_000) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    builder.append(buffer, 0, read)
                }
                val html = builder.toString()
                return html.contains("\"isLive\":true") || 
                       html.contains("\"isLiveStream\":true") || 
                       html.contains("liveBroadcastDetails") || 
                       html.contains("\"isUpcoming\":true") ||
                       html.contains("isLiveBroadcast")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
