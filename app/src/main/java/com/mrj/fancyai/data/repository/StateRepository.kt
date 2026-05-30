package com.mrj.fancyai.data.repository

import android.content.Context
import android.util.Log
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.migration.LegacyStateMigrator
import com.mrj.fancyai.data.seed.DatabaseSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native implementation of the logic found in state.js.
 * Responsible for core data distribution, migration, and initial seeding.
 */
class StateRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val TAG = "StateRepository"

    /**
     * Initializes the app state: migrates legacy data if needed, purges the nuked Root
     * character, and ensures at least one companion is present.
     */
    suspend fun initializeState() = withContext(Dispatchers.IO) {
        try {
            // 1. Migrate legacy state.json if present
            LegacyStateMigrator(context, db, settings).migrateIfNeeded()

            // 2. Purge any leftover Root character (the persona has been nuked)
            DatabaseSeeder(db).seed()

            // 3. Ensure at least one companion exists
            if (db.characterDao().getCount() == 0) {
                seedDefaultCompanion()
            }

        } catch (e: Exception) {
            Log.e(TAG, "State initialization failed", e)
        }
    }

    private suspend fun seedDefaultCompanion() {
        val defaultChar = CharacterEntity(
            id = "c1",
            name = "Companion",
            handle = "@companion",
            bio = "Your first AI companion.",
            persona = "You are a warm, thoughtful companion.",
            timestamp = System.currentTimeMillis()
        )
        db.characterDao().insert(defaultChar)
        Log.d(TAG, "Default companion seeded.")
    }
}
