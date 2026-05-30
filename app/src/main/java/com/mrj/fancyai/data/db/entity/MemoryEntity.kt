package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["charId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("charId")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val charId: String,
    val text: String,
    val category: String = "general",
    val timestamp: Long = System.currentTimeMillis()
)
