package com.mrj.fancyai.data.repository

import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val db: AppDatabase) {
    fun getMessagesForCharacter(charId: String): Flow<List<MessageEntity>> =
        db.messageDao().getMessages(charId)

    suspend fun insertMessage(message: MessageEntity) {
        db.messageDao().insert(message)
    }

    suspend fun deleteMessage(id: String) {
        db.messageDao().deleteById(id)
    }

    suspend fun clearCharacterChat(charId: String) {
        db.messageDao().deleteByCharId(charId)
    }
}
