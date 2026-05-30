package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reply/comment on a social post. The author is either the user ([authorId] == "user")
 * or a character (its id). [authorName] is denormalized for cheap display.
 */
@Entity(
    tableName = "social_comments",
    foreignKeys = [
        ForeignKey(
            entity = SocialPostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("postId"), Index("timestamp")]
)
data class SocialCommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,      // "user" or a character id
    val authorName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
