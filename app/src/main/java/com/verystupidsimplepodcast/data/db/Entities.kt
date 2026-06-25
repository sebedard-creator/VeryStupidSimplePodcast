package com.verystupidsimplepodcast.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rssUrl: String,
    val title: String,
    val imageUrl: String
)

@Entity(
    tableName = "episodes",
    indices = [Index("subscriptionId")]
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val guid: String,
    val title: String,
    val pubDate: Long, // timestamp
    val durationMs: Long,
    val progressMs: Long = 0,
    val isCompleted: Boolean = false,
    val audioUrl: String
)
