package com.mrj.fancyai.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.DossierEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.db.entity.SocialCommentEntity
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-app backup & restore. A backup is a single `.zip` containing one `data.json`
 * (all Room rows + every preference) and a `media/` folder with the image files. Restore
 * wipes the database (children cascade), re-inserts every row, replaces media, and re-applies
 * settings — all through the live Room instance, so no process restart is required.
 */
class BackupService(
    private val db: AppDatabase,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val context: Context
) {
    /** Full JSON payload of the database plus type-tagged settings. */
    private data class BackupData(
        val version: Int = 1,
        val characters: List<CharacterEntity> = emptyList(),
        val messages: List<MessageEntity> = emptyList(),
        val memories: List<MemoryEntity> = emptyList(),
        val dossiers: List<DossierEntity> = emptyList(),
        val posts: List<SocialPostEntity> = emptyList(),
        val comments: List<SocialCommentEntity> = emptyList(),
        val settings: String = "{}"
    )

    private val gson = Gson()

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val data = BackupData(
                characters = db.characterDao().getAllOnce(),
                messages = db.messageDao().getAllOnce(),
                memories = db.memoryDao().getAllOnce(),
                dossiers = db.dossierDao().getAllOnce(),
                posts = db.socialPostDao().getAllOnce(),
                comments = db.socialCommentDao().getAllOnce(),
                settings = settingsRepository.exportToJson()
            )
            val mediaFiles = mediaRepository.getAllImages()

            val resolver = context.contentResolver
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open backup destination")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(gson.toJson(data).toByteArray())
                zip.closeEntry()

                mediaFiles.forEach { file ->
                    zip.putNextEntry(ZipEntry("media/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            Log.d(TAG, "Backup wrote ${data.characters.size} characters, ${mediaFiles.size} media files")
            data.characters.size
        }.onFailure { Log.e(TAG, "Backup failed", it) }
    }

    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open backup file")

            var dataJson: String? = null
            val media = LinkedHashMap<String, ByteArray>()
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "data.json" -> dataJson = zip.readBytes().decodeToString()
                        entry.name.startsWith("media/") && !entry.isDirectory ->
                            media[entry.name.removePrefix("media/")] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val json = dataJson ?: throw IllegalStateException("Not a Fancy AI backup (no data.json)")
            val data = gson.fromJson(json, BackupData::class.java)

            // Replace DB contents. Deleting characters cascades to all child tables; insert
            // parents before children so foreign keys hold.
            db.characterDao().deleteAll()
            data.characters.forEach { db.characterDao().insert(it) }
            data.messages.forEach { db.messageDao().insert(it) }
            data.memories.forEach { db.memoryDao().insert(it) }
            data.dossiers.forEach { db.dossierDao().insert(it) }
            data.posts.forEach { db.socialPostDao().insert(it) }
            data.comments.forEach { db.socialCommentDao().insert(it) }

            // Replace media.
            mediaRepository.clearMedia()
            media.forEach { (name, bytes) -> mediaRepository.writeMediaFile(name, bytes) }

            // Re-apply settings.
            settingsRepository.importFromJson(data.settings)

            Log.d(TAG, "Restore loaded ${data.characters.size} characters, ${media.size} media files")
            data.characters.size
        }.onFailure { Log.e(TAG, "Restore failed", it) }
    }

    companion object {
        private const val TAG = "BackupService"
        /** Suggested filename for a new backup. */
        fun suggestedFileName(): String =
            "fancyai-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.zip"
    }
}
