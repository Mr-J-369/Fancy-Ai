package com.mrj.fancyai.data.seed

import com.mrj.fancyai.data.db.AppDatabase

/**
 * Seeds / cleans up permanent, app-owned data on launch.
 *
 * The "Root" character (`id = "root"`) has been **nuked**. She was previously seeded here
 * as a permanent romance-fantasy companion; that persona is intentionally removed. The id
 * is reserved for future use (an in-app debugging/agent persona), but nothing is seeded
 * under it today. Any Root row left over from a previous install is purged on launch; its
 * messages/dossier/memories cascade-delete with it.
 */
class DatabaseSeeder(private val db: AppDatabase) {

    suspend fun seed() {
        // Remove any leftover Root character from earlier builds. Child rows (messages,
        // dossier, memories, social posts/comments) are removed via ON DELETE CASCADE.
        if (db.characterDao().exists(ROOT_ID)) {
            db.characterDao().deleteById(ROOT_ID)
        }
    }

    companion object {
        const val ROOT_ID = "root"
    }
}
