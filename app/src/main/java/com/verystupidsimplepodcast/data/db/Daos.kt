package com.verystupidsimplepodcast.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions")
    fun getAllSubscriptions(): Flow<List<Subscription>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAllSubscriptionsList(): List<Subscription>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: Subscription): Long

    @Delete
    suspend fun deleteSubscription(subscription: Subscription)

    @Query("SELECT * FROM subscriptions WHERE rssUrl = :url LIMIT 1")
    suspend fun getSubscriptionByUrl(url: String): Subscription?
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes ORDER BY pubDate DESC")
    fun getAllEpisodesChronologically(): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE subscriptionId = :subId ORDER BY pubDate DESC LIMIT 1")
    suspend fun getLatestEpisodeForSubscription(subId: Long): Episode?

    @Query("SELECT * FROM episodes WHERE guid = :guid LIMIT 1")
    suspend fun getEpisodeByGuid(guid: String): Episode?

    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun getEpisodeById(id: Long): Episode?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: Episode)

    @Update
    suspend fun updateEpisode(episode: Episode)

    @Query("UPDATE episodes SET progressMs = :progress, isCompleted = :completed WHERE id = :episodeId")
    suspend fun updateProgress(episodeId: Long, progress: Long, completed: Boolean)

    @Query("UPDATE episodes SET progressMs = :progress, durationMs = :duration, isCompleted = :completed WHERE id = :episodeId")
    suspend fun updateProgressAndDuration(episodeId: Long, progress: Long, duration: Long, completed: Boolean)

    @Query("DELETE FROM episodes WHERE subscriptionId = :subId")
    suspend fun deleteEpisodesBySubscriptionId(subId: Long)

    @Query("DELETE FROM episodes WHERE subscriptionId NOT IN (SELECT id FROM subscriptions)")
    suspend fun deleteOrphanEpisodes()
}
