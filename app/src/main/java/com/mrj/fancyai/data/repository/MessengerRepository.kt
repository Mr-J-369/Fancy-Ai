package com.mrj.fancyai.data.repository

import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.db.entity.DossierEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull

data class InboxItem(
    val character: CharacterEntity,
    val lastMessage: MessageEntity?
)

class MessengerRepository(private val db: AppDatabase) {

    /**
     * Combines all characters with their most recent message for the Inbox view.
     */
    fun getInboxItems(): Flow<List<InboxItem>> {
        val charactersFlow = db.characterDao().getAll()
        val latestFlow = db.messageDao().getLatestPerCharacter()

        return combine(charactersFlow, latestFlow) { characters, latest ->
            val lastByChar = latest.associateBy { it.charId }
            characters.map { character ->
                InboxItem(character, lastByChar[character.id])
            }
        }
    }

    fun getMessages(charId: String): Flow<List<MessageEntity>> =
        db.messageDao().getMessages(charId)

    fun getCharacter(charId: String): Flow<CharacterEntity?> =
        db.characterDao().getById(charId)

    suspend fun insertMessage(message: MessageEntity) {
        db.messageDao().insert(message)
    }

    suspend fun deleteMessage(message: MessageEntity) {
        db.messageDao().delete(message)
    }

    suspend fun saveCharacter(character: CharacterEntity) {
        if (db.characterDao().exists(character.id)) {
            db.characterDao().update(character)
        } else {
            db.characterDao().insert(character)
        }
    }

    suspend fun deleteCharacter(charId: String) {
        db.characterDao().deleteById(charId)
        db.messageDao().deleteByCharId(charId)
    }

    suspend fun clearChat(charId: String) {
        db.messageDao().deleteByCharId(charId)
    }

    // Memory & Dossier Management
    fun getMemories(charId: String): Flow<List<MemoryEntity>> =
        db.memoryDao().getMemories(charId)

    fun getDossier(charId: String): Flow<DossierEntity?> =
        db.dossierDao().getDossier(charId)

    suspend fun saveMemory(memory: MemoryEntity) {
        db.memoryDao().insert(memory)
    }

    suspend fun deleteMemory(memoryId: String) {
        db.memoryDao().deleteById(memoryId)
    }

    suspend fun saveDossier(dossier: DossierEntity) {
        db.dossierDao().insert(dossier)
    }
}
