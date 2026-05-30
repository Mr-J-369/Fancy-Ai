package com.mrj.fancyai.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SocialPostDao {
    @Query("SELECT * FROM social_posts WHERE platform = :platform ORDER BY timestamp DESC")
    fun getPosts(platform: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE platform = :platform ORDER BY timestamp DESC")
    fun getByPlatform(platform: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE charId = :charId ORDER BY timestamp DESC")
    fun getByCharId(charId: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE charId = :charId AND platform = :platform ORDER BY timestamp DESC")
    fun getPostsByCharacter(charId: String, platform: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE id = :id")
    fun getById(id: String): Flow<SocialPostEntity?>

    /** One-shot snapshot of every post — used by the gallery to map images to characters. */
    @Query("SELECT * FROM social_posts")
    suspend fun getAllOnce(): List<SocialPostEntity>

    @Insert
    suspend fun insert(post: SocialPostEntity)

    @Update
    suspend fun update(post: SocialPostEntity)

    @Delete
    suspend fun delete(post: SocialPostEntity)

    @Query("DELETE FROM social_posts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM social_posts WHERE charId = :charId")
    suspend fun deleteByCharId(charId: String)
}
