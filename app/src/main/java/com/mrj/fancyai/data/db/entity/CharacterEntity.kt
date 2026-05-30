package com.mrj.fancyai.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val handle: String = "",
    val bio: String = "",
    val persona: String = "",
    val avatarRef: String? = null,
    val enableRebbit: Boolean = true, // legacy; superseded by the autoPost* opt-ins below
    val followerCount: Int = 0,
    // Per-character autonomous-posting opt-in: which social apps this character may post to.
    val autoPostUstagram: Boolean = true,
    val autoPostRebbit: Boolean = true,
    val autoPostY: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
