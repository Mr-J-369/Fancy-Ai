package com.mrj.fancyai.data.migration

import android.content.Context
import android.util.Log
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.repository.SettingsRepository
import org.json.JSONObject
import java.io.File

/**
 * Migrates data from the legacy state.json (WebView version) to the new native Room database.
 */
class LegacyStateMigrator(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val TAG = "LegacyStateMigrator"

    suspend fun migrateIfNeeded() {
        val stateFile = File(context.filesDir, "state.json")
        if (!stateFile.exists()) return

        Log.d(TAG, "Legacy state.json found. Starting migration...")

        try {
            val jsonStr = stateFile.readText()
            val state = JSONObject(jsonStr)

            // 1. Migrate Characters
            if (state.has("characters")) {
                val chars = state.getJSONArray("characters")
                for (i in 0 until chars.length()) {
                    val c = chars.getJSONObject(i)
                    val id = c.getString("id")
                    if (id == "root") continue // Root has been nuked — never import a legacy one
                    
                    val entity = CharacterEntity(
                        id = id,
                        name = c.optString("name", "Unknown"),
                        handle = c.optString("handle", "@ai"),
                        bio = c.optString("bio", ""),
                        persona = c.optString("persona", ""),
                        avatarRef = if (c.isNull("avatar")) null else c.getString("avatar"),
                        timestamp = System.currentTimeMillis()
                    )
                    db.characterDao().insert(entity)
                }
            }

            // 2. Migrate Sessions (Messages)
            if (state.has("sessions")) {
                val sessions = state.getJSONObject("sessions")
                val keys = sessions.keys()
                while (keys.hasNext()) {
                    val charId = keys.next()
                    val msgs = sessions.getJSONArray(charId)
                    for (i in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(i)
                        val msgEntity = MessageEntity(
                            id = m.optString("id", "mig_" + System.nanoTime()),
                            charId = charId,
                            sender = m.optString("sender", "ai"),
                            text = m.optString("text", ""),
                            type = m.optString("type", "text"),
                            timestamp = m.optLong("timestamp", System.currentTimeMillis())
                        )
                        db.messageDao().insert(msgEntity)
                    }
                }
            }

            // 3. Migrate Settings (Partial)
            if (state.has("settings")) {
                val s = state.getJSONObject("settings")
                if (s.has("provider")) settings.llmProvider = s.getString("provider")
                if (s.has("temperature")) settings.temperature = s.getDouble("temperature").toFloat()
            }

            // Rename state.json so we don't migrate again
            stateFile.renameTo(File(context.filesDir, "state.json.migrated"))
            Log.d(TAG, "Migration complete. state.json renamed to state.json.migrated")

        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }
}
