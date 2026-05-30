package com.mrj.fancyai.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mrj.fancyai.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE charId = :charId ORDER BY timestamp DESC LIMIT 100")
    fun getMessages(charId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun getById(id: String): Flow<MessageEntity?>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE charId = :charId")
    suspend fun deleteByCharId(charId: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    /** One-shot snapshot of all messages (for backup). */
    @Query("SELECT * FROM messages")
    suspend fun getAllOnce(): List<MessageEntity>

    /** The single most-recent message per character — for the inbox, without loading all rows. */
    @Query(
        "SELECT m.* FROM messages m INNER JOIN " +
            "(SELECT charId, MAX(timestamp) AS maxTs FROM messages GROUP BY charId) latest " +
            "ON m.charId = latest.charId AND m.timestamp = latest.maxTs GROUP BY m.charId"
    )
    fun getLatestPerCharacter(): Flow<List<MessageEntity>>
}
