package com.mrj.fancyai.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.mrj.fancyai.data.db.entity.SocialCommentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SocialCommentDao {
    @Query("SELECT * FROM social_comments WHERE postId = :postId ORDER BY timestamp ASC")
    fun getComments(postId: String): Flow<List<SocialCommentEntity>>

    /** One-shot snapshot of all comments (for backup). */
    @Query("SELECT * FROM social_comments")
    suspend fun getAllOnce(): List<SocialCommentEntity>

    @Insert
    suspend fun insert(comment: SocialCommentEntity)

    @Delete
    suspend fun delete(comment: SocialCommentEntity)

    @Query("DELETE FROM social_comments WHERE id = :id")
    suspend fun deleteById(id: String)
}
