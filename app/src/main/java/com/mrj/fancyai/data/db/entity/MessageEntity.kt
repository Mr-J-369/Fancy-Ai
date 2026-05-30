package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["charId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("charId"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val charId: String,
    val sender: String,  // "user" or "ai"
    val text: String,
    val type: String = "text",  // "text" or "image"
    val timestamp: Long = System.currentTimeMillis()
)
