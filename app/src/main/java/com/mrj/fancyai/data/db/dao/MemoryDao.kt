package com.mrj.fancyai.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mrj.fancyai.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE charId = :charId ORDER BY timestamp DESC")
    fun getMemories(charId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE charId = :charId ORDER BY timestamp DESC LIMIT 50")
    fun getRecentMemories(charId: String): Flow<List<MemoryEntity>>

    /** One-shot snapshot of all memories (for backup). */
    @Query("SELECT * FROM memories")
    suspend fun getAllOnce(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity)

    @Update
    suspend fun update(memory: MemoryEntity)

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE charId = :charId")
    suspend fun deleteByCharId(charId: String)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)
}
