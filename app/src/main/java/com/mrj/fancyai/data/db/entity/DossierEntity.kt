package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "dossiers",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["charId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DossierEntity(
    @PrimaryKey val charId: String,
    val dossierJson: String = "{}",  // Store as JSON: {relationship, user_traits, world_facts, milestones}
    val timestamp: Long = System.currentTimeMillis()
)
