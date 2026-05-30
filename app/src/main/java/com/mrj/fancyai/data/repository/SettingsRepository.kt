package com.mrj.fancyai.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A named, reusable global system prompt. */
data class SystemPromptPreset(val name: String, val text: String)

class SettingsRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "fancy_ai_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var llmProvider: String
        get() = prefs.getString("llm_provider", "llama") ?: "llama"
        set(value) = prefs.edit().putString("llm_provider", value).apply()

    var modelPath: String
        get() = prefs.getString("model_path", "") ?: ""
        set(value) = prefs.edit().putString("model_path", value).apply()

    var contextSize: Int
        get() = prefs.getInt("context_size", 4096)
        set(value) = prefs.edit().putInt("context_size", value).apply()

    var kvCacheType: Int
        get() = prefs.getInt("kv_cache_type", 0)
        set(value) = prefs.edit().putInt("kv_cache_type", value).apply()

    var threadCount: Int
        get() = prefs.getInt("thread_count", 6)
        set(value) = prefs.edit().putInt("thread_count", value).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()

    var topK: Int
        get() = prefs.getInt("top_k", 40)
        set(value) = prefs.edit().putInt("top_k", value).apply()

    var topP: Float
        get() = prefs.getFloat("top_p", 0.95f)
        set(value) = prefs.edit().putFloat("top_p", value).apply()

    var gpuLayers: Int
        get() = prefs.getInt("gpu_layers", 0)
        set(value) = prefs.edit().putInt("gpu_layers", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var customBackendUrl: String
        get() = prefs.getString("custom_backend_url", "") ?: ""
        set(value) = prefs.edit().putString("custom_backend_url", value).apply()

    var cloudModel: String // Model id for cloud/HTTP providers (deepinfra/openrouter/localllm/custom)
        get() = prefs.getString("cloud_model", "") ?: ""
        set(value) = prefs.edit().putString("cloud_model", value).apply()

    var hardwareBackend: Int // 0=CPU, 1=NPU, 2=GPU
        get() = prefs.getInt("hardware_backend", 0)
        set(value) = prefs.edit().putInt("hardware_backend", value).apply()

    var socialPostFrequency: Int // Hours (legacy)
        get() = prefs.getInt("social_post_frequency", 4)
        set(value) = prefs.edit().putInt("social_post_frequency", value).apply()

    var socialPostIntervalMinutes: Int // Auto-post cadence, free-entry in minutes
        get() = prefs.getInt("social_post_interval_min", 240)
        set(value) = prefs.edit().putInt("social_post_interval_min", value).apply()

    private val _autoPostEnabled = MutableStateFlow(prefs.getBoolean("auto_post_enabled", false))
    val autoPostEnabledFlow: StateFlow<Boolean> = _autoPostEnabled.asStateFlow()

    var autoPostEnabled: Boolean
        get() = _autoPostEnabled.value
        set(value) {
            prefs.edit().putBoolean("auto_post_enabled", value).apply()
            _autoPostEnabled.value = value
        }

    /** Subreddits a character may auto-post to (defaults to all of [SocialDefaults.SUBREDDITS]). */
    var rebbitSubreddits: Set<String>
        get() = prefs.getStringSet("rebbit_subreddits", null)
            ?: com.mrj.fancyai.service.SocialDefaults.SUBREDDITS.toSet()
        set(value) = prefs.edit().putStringSet("rebbit_subreddits", value).apply()

    var autoEvolveDossier: Boolean
        get() = prefs.getBoolean("auto_evolve_dossier", true)
        set(value) = prefs.edit().putBoolean("auto_evolve_dossier", value).apply()

    var wallpaperMode: String
        get() = prefs.getString("wallpaper_mode", "mesh") ?: "mesh"
        set(value) = prefs.edit().putString("wallpaper_mode", value).apply()

    /**
     * App theme: "system" (follow device), "light", or "dark". Backed by a StateFlow so the
     * Activity can re-theme live the moment the setting changes.
     */
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "system") ?: "system")
    val themeModeFlow: StateFlow<String> = _themeMode.asStateFlow()

    var themeMode: String
        get() = _themeMode.value
        set(value) {
            prefs.edit().putString("theme_mode", value).apply()
            _themeMode.value = value
        }

    /**
     * NSFW master switch. When off (default), the Rebbit app is hidden from the launcher and
     * excluded from autonomous posting. Backed by a StateFlow so the home screen reacts live.
     */
    private val _nsfwEnabled = MutableStateFlow(prefs.getBoolean("nsfw_enabled", false))
    val nsfwEnabledFlow: StateFlow<Boolean> = _nsfwEnabled.asStateFlow()

    var nsfwEnabled: Boolean
        get() = _nsfwEnabled.value
        set(value) {
            prefs.edit().putBoolean("nsfw_enabled", value).apply()
            _nsfwEnabled.value = value
        }

    var userName: String
        get() = prefs.getString("user_name", "User") ?: "User"
        set(value) = prefs.edit().putString("user_name", value).apply()

    var userBio: String
        get() = prefs.getString("user_bio", "") ?: ""
        set(value) = prefs.edit().putString("user_bio", value).apply()

    // --- Global system prompts (multiple named presets, one active) ---

    /** Effective system prompt: the text of the currently-active preset. Read-only; callers
     *  (PromptBuilder, AutoGenerationService, PhoneViewModel) keep using this unchanged. */
    val systemPrompt: String
        get() = loadSystemPrompts().firstOrNull { it.name == activeSystemPromptName }?.text ?: ""

    var activeSystemPromptName: String
        get() {
            val presets = loadSystemPrompts()
            val active = prefs.getString("active_system_prompt", null)
            return if (active != null && presets.any { it.name == active }) active
            else presets.firstOrNull()?.name ?: "Default"
        }
        set(value) = prefs.edit().putString("active_system_prompt", value).apply()

    /** All saved presets (always at least one). */
    fun loadSystemPrompts(): List<SystemPromptPreset> {
        val json = prefs.getString("system_prompts", null)
        if (json.isNullOrBlank()) {
            // First run / migration: seed from the legacy single "system_prompt" value.
            val legacy = prefs.getString("system_prompt", "") ?: ""
            val seed = listOf(SystemPromptPreset("Default", legacy))
            saveSystemPrompts(seed)
            if (prefs.getString("active_system_prompt", null) == null) {
                prefs.edit().putString("active_system_prompt", "Default").apply()
            }
            return seed
        }
        return try {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SystemPromptPreset(o.optString("name"), o.optString("text"))
            }.ifEmpty { listOf(SystemPromptPreset("Default", "")) }
        } catch (e: Exception) {
            listOf(SystemPromptPreset("Default", ""))
        }
    }

    private fun saveSystemPrompts(list: List<SystemPromptPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("name", it.name).put("text", it.text)) }
        prefs.edit().putString("system_prompts", arr.toString()).apply()
    }

    /** Updates the text of an existing preset (no-op if the name isn't found). */
    fun setSystemPromptText(name: String, text: String) {
        val list = loadSystemPrompts().map { if (it.name == name) it.copy(text = text) else it }
        saveSystemPrompts(list)
    }

    /** Creates a new uniquely-named preset and returns its name. */
    fun addSystemPrompt(baseName: String = "New prompt"): String {
        val list = loadSystemPrompts().toMutableList()
        var name = baseName
        var n = 2
        while (list.any { it.name == name }) { name = "$baseName $n"; n++ }
        list.add(SystemPromptPreset(name, ""))
        saveSystemPrompts(list)
        return name
    }

    /** Renames a preset, keeping the active pointer in sync. Ignored if the new name is blank/dup. */
    fun renameSystemPrompt(oldName: String, newName: String) {
        val clean = newName.trim()
        val list = loadSystemPrompts()
        if (clean.isBlank() || list.any { it.name == clean }) return
        saveSystemPrompts(list.map { if (it.name == oldName) it.copy(name = clean) else it })
        if (activeSystemPromptName == oldName) activeSystemPromptName = clean
    }

    /** Deletes a preset; never removes the last one. Re-points active if needed. */
    fun deleteSystemPrompt(name: String) {
        val list = loadSystemPrompts()
        if (list.size <= 1) return
        val remaining = list.filterNot { it.name == name }
        saveSystemPrompts(remaining)
        if (activeSystemPromptName == name) activeSystemPromptName = remaining.first().name
    }

    var chatTemplate: String
        get() = prefs.getString("chat_template", "chatml") ?: "chatml"
        set(value) = prefs.edit().putString("chat_template", value).apply()

    var historyCap: Int
        get() = prefs.getInt("history_cap", 8)
        set(value) = prefs.edit().putInt("history_cap", value).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 512)
        set(value) = prefs.edit().putInt("max_tokens", value).apply()

    // --- Imaging Studio Settings ---
    var useLocalDream: Boolean
        get() = prefs.getBoolean("use_localdream", false)
        set(value) = prefs.edit().putBoolean("use_localdream", value).apply()

    var localDreamUrl: String
        get() = prefs.getString("localdream_url", "http://127.0.0.1:8081") ?: "http://127.0.0.1:8081"
        set(value) = prefs.edit().putString("localdream_url", value).apply()

    var localDreamScheduler: String
        get() = prefs.getString("localdream_scheduler", "dpm") ?: "dpm"
        set(value) = prefs.edit().putString("localdream_scheduler", value).apply()

    var forgeUrl: String
        get() = prefs.getString("forge_url", "http://127.0.0.1:7860") ?: "http://127.0.0.1:7860"
        set(value) = prefs.edit().putString("forge_url", value).apply()

    var imgWidth: Int
        get() = prefs.getInt("img_width", 512)
        set(value) = prefs.edit().putInt("img_width", value).apply()

    var imgHeight: Int
        get() = prefs.getInt("img_height", 512)
        set(value) = prefs.edit().putInt("img_height", value).apply()

    var imgSteps: Int
        get() = prefs.getInt("img_steps", 20)
        set(value) = prefs.edit().putInt("img_steps", value).apply()

    var imgCfg: Float
        get() = prefs.getFloat("img_cfg", 7.0f)
        set(value) = prefs.edit().putFloat("img_cfg", value).apply()

    var imgDenoising: Float
        get() = prefs.getFloat("img_denoising", 0.75f)
        set(value) = prefs.edit().putFloat("img_denoising", value).apply()

    // --- Backup / restore of all settings (type-tagged so values round-trip exactly) ---

    /** Serializes every stored preference to JSON, tagging each value with its type. */
    fun exportToJson(): String {
        val out = org.json.JSONObject()
        prefs.all.forEach { (key, value) ->
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value.map { it.toString() }))
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out.toString()
    }

    /** Restores preferences from [exportToJson] output, preserving each value's original type. */
    fun importFromJson(json: String) {
        val obj = org.json.JSONObject(json)
        val editor = prefs.edit()
        obj.keys().forEach { key ->
            val entry = obj.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
                "ss" -> {
                    val arr = entry.optJSONArray("v") ?: org.json.JSONArray()
                    editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        editor.apply()
        // Refresh the cached reactive prefs so theme/NSFW reflect the restored values.
        _themeMode.value = prefs.getString("theme_mode", "system") ?: "system"
        _nsfwEnabled.value = prefs.getBoolean("nsfw_enabled", false)
        _autoPostEnabled.value = prefs.getBoolean("auto_post_enabled", false)
    }
}
