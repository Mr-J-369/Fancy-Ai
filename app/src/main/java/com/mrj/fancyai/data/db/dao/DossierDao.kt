package com.mrj.fancyai.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mrj.fancyai.data.db.entity.DossierEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DossierDao {
    @Query("SELECT * FROM dossiers WHERE charId = :charId")
    fun getDossier(charId: String): Flow<DossierEntity?>

    /** One-shot snapshot of all dossiers (for backup). */
    @Query("SELECT * FROM dossiers")
    suspend fun getAllOnce(): List<DossierEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dossier: DossierEntity)

    @Update
    suspend fun update(dossier: DossierEntity)

    @Query("DELETE FROM dossiers WHERE charId = :charId")
    suspend fun delete(charId: String)
}
