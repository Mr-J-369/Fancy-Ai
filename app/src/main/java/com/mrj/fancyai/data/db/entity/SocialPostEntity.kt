package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "social_posts",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["charId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("charId"), Index("platform"), Index("timestamp")]
)
data class SocialPostEntity(
    @PrimaryKey val id: String,
    val charId: String,
    val platform: String,  // "ustagram" | "rebbit" | "y"
    val caption: String? = null,
    val imageRef: String? = null,
    val title: String? = null,
    val subreddit: String? = null,
    val text: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
