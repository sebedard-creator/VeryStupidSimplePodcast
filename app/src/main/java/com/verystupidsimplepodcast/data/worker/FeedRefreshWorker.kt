package com.verystupidsimplepodcast.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.verystupidsimplepodcast.data.db.PodcastDatabase
import com.verystupidsimplepodcast.data.network.PodcastRepository

class FeedRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = PodcastDatabase.getDatabase(applicationContext)
            val repository = PodcastRepository(database.subscriptionDao(), database.episodeDao())
            
            repository.refreshAllFeeds()
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
