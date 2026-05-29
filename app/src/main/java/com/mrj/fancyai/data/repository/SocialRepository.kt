package com.mrj.fancyai.data.repository

import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import kotlinx.coroutines.flow.Flow

class SocialRepository(private val db: AppDatabase) {

    fun getPostsByPlatform(platform: String): Flow<List<SocialPostEntity>> =
        db.socialPostDao().getByPlatform(platform)

    fun getPostsByCharacter(charId: String): Flow<List<SocialPostEntity>> =
        db.socialPostDao().getByCharId(charId)

    suspend fun insertPost(post: SocialPostEntity) {
        db.socialPostDao().insert(post)
    }

    suspend fun updatePost(post: SocialPostEntity) {
        db.socialPostDao().update(post)
    }

    suspend fun deletePost(id: String) {
        db.socialPostDao().deleteById(id)
    }
}
