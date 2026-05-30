package com.mrj.fancyai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mrj.fancyai.data.db.dao.CharacterDao
import com.mrj.fancyai.data.db.dao.MessageDao
import com.mrj.fancyai.data.db.dao.MemoryDao
import com.mrj.fancyai.data.db.dao.DossierDao
import com.mrj.fancyai.data.db.dao.SocialPostDao
import com.mrj.fancyai.data.db.dao.SocialCommentDao
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import com.mrj.fancyai.data.db.entity.DossierEntity
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.data.db.entity.SocialCommentEntity

@Database(
    entities = [
        CharacterEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        DossierEntity::class,
        SocialPostEntity::class,
        SocialCommentEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun dossierDao(): DossierDao
    abstract fun socialPostDao(): SocialPostDao
    abstract fun socialCommentDao(): SocialCommentDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2: adds the social_comments table (post replies). Written by hand so
         * existing characters/chats/posts are preserved across the upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `social_comments` (" +
                        "`id` TEXT NOT NULL, `postId` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
                        "`authorName` TEXT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`postId`) REFERENCES `social_posts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_comments_postId` ON `social_comments` (`postId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_social_comments_timestamp` ON `social_comments` (`timestamp`)")
            }
        }

        /**
         * v2 → v3: per-character autonomous-posting opt-ins (which social apps a character
         * may auto-post to). Defaults to enabled for all three so existing characters keep
         * their current behavior.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN autoPostUstagram INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN autoPostRebbit INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE characters ADD COLUMN autoPostY INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fancyai.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Safety net only — real migrations above are preferred and run first.
                    .fallbackToDestructiveMigration(true)
                    .build().also { instance = it }
            }
    }
}
