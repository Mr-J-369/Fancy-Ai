package com.mrj.fancyai.data.repository

import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.CharacterEntity
import kotlinx.coroutines.flow.Flow

class CharacterRepository(private val db: AppDatabase) {
    fun getAllCharacters(): Flow<List<CharacterEntity>> = db.characterDao().getAll()

    fun getCharacter(id: String): Flow<CharacterEntity?> = db.characterDao().getById(id)

    suspend fun insertCharacter(character: CharacterEntity) {
        db.characterDao().insert(character)
    }

    suspend fun updateCharacter(character: CharacterEntity) {
        db.characterDao().update(character)
    }

    suspend fun deleteCharacter(id: String) {
        db.characterDao().deleteById(id)
    }
}
