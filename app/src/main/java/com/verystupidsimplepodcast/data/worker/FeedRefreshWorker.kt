package com.verystupidsimplepodcast.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.verystupidsimplepodcast.data.db.PodcastDatabase
import com.verystupidsimplepodcast.data.network.PodcastRepository
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.verystupidsimplepodcast.R

class FeedRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = PodcastDatabase.getDatabase(applicationContext)
            val repository = PodcastRepository(database.subscriptionDao(), database.episodeDao())
            
            val newEpisodes = repository.refreshAllFeeds()
            
            if (newEpisodes.isNotEmpty()) {
                showNotification(newEpisodes)
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(newEpisodes: List<com.verystupidsimplepodcast.data.db.Episode>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val channelId = "podcast_updates_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Nouveaux Épisodes",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (newEpisodes.size == 1) {
            "Nouvel épisode"
        } else {
            "${newEpisodes.size} nouveaux épisodes"
        }

        val content = if (newEpisodes.size == 1) {
            newEpisodes.first().title
        } else {
            "De nouveaux épisodes ont été ajoutés à vos abonnements."
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
