package com.mrj.fancyai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mrj.fancyai.data.db.dao.CharacterDao
import com.mrj.fancyai.data.db.dao.MessageDao
import com.mrj.fancyai.data.db.dao.MemoryDao
import com.mrj.fancyai.data.db.dao.DossierDao
import com.mrj.fancyai.data.db.dao.SocialPostDao
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import com.mrj.fancyai.data.db.entity.DossierEntity
import com.mrj.fancyai.data.db.entity.SocialPostEntity

@Database(
    entities = [
        CharacterEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        DossierEntity::class,
        SocialPostEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun dossierDao(): DossierDao
    abstract fun socialPostDao(): SocialPostDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fancy_ai.db"
                ).build().also { instance = it }
            }
    }
}
