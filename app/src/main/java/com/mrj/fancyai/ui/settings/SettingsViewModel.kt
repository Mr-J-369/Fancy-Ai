package com.mrj.fancyai.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.domain.inference.ModelLoader
import com.mrj.fancyai.LlamaInference
import com.mrj.fancyai.service.CloudLlmService
import kotlinx.coroutines.launch

/**
 * Local Dream scheduler options, mirrored from the legacy imaging.js dropdown.
 * `first` is the value persisted/sent to the backend, `second` is the display label.
 */
/**
 * LLM providers. `first` is persisted to settings; `second` is the display label.
 * "llama" = on-device llama.cpp; the rest are OpenAI-compatible HTTP endpoints.
 */
val LLM_PROVIDERS: List<Pair<String, String>> = listOf(
    "llama" to "On-Device (llama.cpp)",
    "deepinfra" to "DeepInfra (Cloud)",
    "openrouter" to "OpenRouter (Cloud)",
    "localllm" to "Local LLM (HTTP)",
    "custom" to "Custom Endpoint"
)

/** Providers that expose a fetchable model catalogue. */
val MODEL_FETCH_PROVIDERS = setOf("deepinfra", "openrouter")

val LOCAL_DREAM_SCHEDULERS: List<Pair<String, String>> = listOf(
    "dpm" to "DPM++ 2M (Default)",
    "dpm_karras" to "DPM++ 2M + Karras",
    "dpm_sde" to "DPM++ 2M SDE",
    "dpm_sde_karras" to "DPM++ 2M SDE + Karras",
    "euler_a" to "Euler A",
    "euler_a_karras" to "Euler A + Karras",
    "euler" to "Euler",
    "euler_karras" to "Euler + Karras",
    "lcm" to "LCM"
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelLoader: ModelLoader,
    private val cloudLlmService: CloudLlmService,
    private val backupService: com.mrj.fancyai.service.BackupService
) : ViewModel() {

    // --- On-device model ---
    var loadedModel by mutableStateOf(modelLoader.loadedModelName())
        private set

    var availableModels by mutableStateOf(modelLoader.availableModels())
        private set

    var modelStatus by mutableStateOf<String?>(null)
        private set

    var isModelBusy by mutableStateOf(false)
        private set

    /** Imports and loads a GGUF picked via the system file picker. */
    fun importModel(uri: Uri) {
        if (isModelBusy) return
        isModelBusy = true
        modelStatus = "Importing…"
        viewModelScope.launch {
            val result = modelLoader.importAndLoad(uri) { progress -> modelStatus = progress }
            result
                .onSuccess { name ->
                    loadedModel = modelLoader.loadedModelName()
                    availableModels = modelLoader.availableModels()
                    modelPath = settingsRepository.modelPath
                    chatTemplate = settingsRepository.chatTemplate // may have been auto-detected
                    modelStatus = "Loaded: $name (template: $chatTemplate)"
                }
                .onFailure { modelStatus = "Failed: ${it.message}" }
            isModelBusy = false
        }
    }

    /** Frees the loaded on-device model from memory. */
    fun unloadModel() {
        if (isModelBusy) return
        modelLoader.unload()
        loadedModel = modelLoader.loadedModelName()
        modelStatus = "Model unloaded"
    }

    /** Deletes an imported model file from storage (unloading it first if it's active). */
    fun deleteModel(name: String) {
        if (isModelBusy) return
        isModelBusy = true
        modelStatus = "Deleting $name…"
        viewModelScope.launch {
            val ok = modelLoader.deleteModel(name)
            loadedModel = modelLoader.loadedModelName()
            availableModels = modelLoader.availableModels()
            modelStatus = if (ok) "Deleted $name" else "Couldn't delete $name"
            isModelBusy = false
        }
    }

    /** Loads an already-imported model by file name. */
    fun loadModel(name: String) {
        if (isModelBusy) return
        isModelBusy = true
        modelStatus = "Loading $name…"
        viewModelScope.launch {
            val ok = modelLoader.loadByName(name)
            loadedModel = modelLoader.loadedModelName()
            chatTemplate = settingsRepository.chatTemplate // may have been auto-detected
            modelStatus = if (ok) "Loaded: $name (template: $chatTemplate)" else "Failed to load $name"
            isModelBusy = false
        }
    }

    var llmProvider by mutableStateOf(settingsRepository.llmProvider)
        private set

    var modelPath by mutableStateOf(settingsRepository.modelPath)
        private set

    var contextSize by mutableStateOf(settingsRepository.contextSize.toString())
        private set

    var threadCount by mutableStateOf(settingsRepository.threadCount.toString())
        private set

    var temperature by mutableStateOf(settingsRepository.temperature.toString())
        private set

    var topK by mutableStateOf(settingsRepository.topK.toString())
        private set

    var topP by mutableStateOf(settingsRepository.topP.toString())
        private set

    var gpuLayers by mutableStateOf(settingsRepository.gpuLayers.toString())
        private set

    var apiKey by mutableStateOf(settingsRepository.apiKey)
        private set

    var customBackendUrl by mutableStateOf(settingsRepository.customBackendUrl)
        private set

    // --- Cloud / HTTP LLM model selection ---
    var cloudModel by mutableStateOf(settingsRepository.cloudModel)
        private set
    var fetchedModels by mutableStateOf<List<String>>(emptyList())
        private set
    var isFetchingModels by mutableStateOf(false)
        private set
    var modelFetchError by mutableStateOf<String?>(null)
        private set

    var hardwareBackend by mutableStateOf(settingsRepository.hardwareBackend)
        private set

    var kvCacheType by mutableStateOf(settingsRepository.kvCacheType)
        private set

    var socialPostFrequency by mutableStateOf(settingsRepository.socialPostFrequency.toString())
        private set

    var autoPostEnabled by mutableStateOf(settingsRepository.autoPostEnabled)
        private set

    var socialPostIntervalMinutes by mutableStateOf(settingsRepository.socialPostIntervalMinutes.toString())
        private set

    var autoEvolveDossier by mutableStateOf(settingsRepository.autoEvolveDossier)
        private set

    var wallpaperMode by mutableStateOf(settingsRepository.wallpaperMode)
        private set

    // App theme: "system" / "light" / "dark".
    var themeMode by mutableStateOf(settingsRepository.themeMode)
        private set

    // NSFW master switch (hides Rebbit when off).
    var nsfwEnabled by mutableStateOf(settingsRepository.nsfwEnabled)
        private set

    // Rebbit subreddit selection (which subs characters may auto-post to).
    val allSubreddits: List<String> = com.mrj.fancyai.service.SocialDefaults.SUBREDDITS
    var enabledSubreddits by mutableStateOf(settingsRepository.rebbitSubreddits)
        private set

    // --- Conversation / prompt-building ---
    var userName by mutableStateOf(settingsRepository.userName)
        private set

    var userBio by mutableStateOf(settingsRepository.userBio)
        private set

    // --- Global system prompts (multiple named presets) ---
    var systemPromptNames by mutableStateOf(settingsRepository.loadSystemPrompts().map { it.name })
        private set
    var activeSystemPrompt by mutableStateOf(settingsRepository.activeSystemPromptName)
        private set
    var systemPromptText by mutableStateOf(settingsRepository.systemPrompt)
        private set

    var chatTemplate by mutableStateOf(settingsRepository.chatTemplate)
        private set

    var historyCap by mutableStateOf(settingsRepository.historyCap.toString())
        private set

    var maxTokens by mutableStateOf(settingsRepository.maxTokens.toString())
        private set

    // --- Imaging Studio ---
    var useLocalDream by mutableStateOf(settingsRepository.useLocalDream)
        private set
    var localDreamUrl by mutableStateOf(settingsRepository.localDreamUrl)
        private set
    var localDreamScheduler by mutableStateOf(settingsRepository.localDreamScheduler)
        private set
    var forgeUrl by mutableStateOf(settingsRepository.forgeUrl)
        private set

    fun updateLlmProvider(value: String) {
        val previous = llmProvider
        llmProvider = value
        settingsRepository.llmProvider = value
        // Model catalogue is provider-specific; clear it when switching.
        fetchedModels = emptyList()
        modelFetchError = null
        // Free the on-device model's memory when moving to a cloud/HTTP provider.
        if (previous == "llama" && value != "llama" && modelLoader.loadedModelName() != null) {
            modelLoader.unload()
            loadedModel = null
            modelStatus = "On-device model unloaded (switched to $value)"
        }
    }

    fun updateModelPath(value: String) {
        modelPath = value
        settingsRepository.modelPath = value
    }

    fun updateContextSize(value: String) {
        contextSize = value
        value.toIntOrNull()?.let { 
            settingsRepository.contextSize = it
            applyHardwareParams()
        }
    }

    fun updateThreadCount(value: String) {
        threadCount = value
        value.toIntOrNull()?.let { 
            settingsRepository.threadCount = it
            applyHardwareParams()
        }
    }

    fun updateTemperature(value: String) {
        temperature = value
        value.toFloatOrNull()?.let { settingsRepository.temperature = it }
    }

    fun updateTopK(value: String) {
        topK = value
        value.toIntOrNull()?.let { settingsRepository.topK = it }
    }

    fun updateTopP(value: String) {
        topP = value
        value.toFloatOrNull()?.let { settingsRepository.topP = it }
    }

    fun updateGpuLayers(value: String) {
        gpuLayers = value
        value.toIntOrNull()?.let { 
            settingsRepository.gpuLayers = it
            applyHardwareParams()
        }
    }

    fun updateApiKey(value: String) {
        apiKey = value
        settingsRepository.apiKey = value
    }

    fun updateCustomBackendUrl(value: String) {
        customBackendUrl = value
        settingsRepository.customBackendUrl = value
    }

    fun updateCloudModel(value: String) {
        cloudModel = value
        settingsRepository.cloudModel = value
    }

    /** Fetches the model catalogue for the current cloud provider into [fetchedModels]. */
    fun fetchModels() {
        if (isFetchingModels) return
        isFetchingModels = true
        modelFetchError = null
        viewModelScope.launch {
            cloudLlmService.fetchModels(llmProvider, apiKey)
                .onSuccess { models ->
                    fetchedModels = models
                    if (models.isEmpty()) modelFetchError = "No models returned"
                    // Default the selection if nothing valid is chosen yet.
                    if (cloudModel.isBlank() || cloudModel !in models) {
                        models.firstOrNull()?.let { updateCloudModel(it) }
                    }
                }
                .onFailure { modelFetchError = it.message ?: "Failed to fetch models" }
            isFetchingModels = false
        }
    }

    fun updateHardwareBackend(value: Int) {
        hardwareBackend = value
        settingsRepository.hardwareBackend = value
        applyHardwareParams()
    }

    fun updateKvCacheType(value: Int) {
        kvCacheType = value
        settingsRepository.kvCacheType = value
        applyHardwareParams()
    }

    fun updateSocialPostFrequency(value: String) {
        socialPostFrequency = value
        value.toIntOrNull()?.let { settingsRepository.socialPostFrequency = it }
    }

    fun updateAutoPostEnabled(value: Boolean) {
        autoPostEnabled = value
        settingsRepository.autoPostEnabled = value
    }

    fun updateSocialPostIntervalMinutes(value: String) {
        socialPostIntervalMinutes = value
        value.toIntOrNull()?.let { settingsRepository.socialPostIntervalMinutes = it }
    }

    fun updateAutoEvolveDossier(value: Boolean) {
        autoEvolveDossier = value
        settingsRepository.autoEvolveDossier = value
    }

    fun updateWallpaperMode(value: String) {
        wallpaperMode = value
        settingsRepository.wallpaperMode = value
    }

    fun updateThemeMode(value: String) {
        themeMode = value
        settingsRepository.themeMode = value
    }

    fun updateNsfwEnabled(value: Boolean) {
        nsfwEnabled = value
        settingsRepository.nsfwEnabled = value
    }

    fun toggleSubreddit(sub: String, enabled: Boolean) {
        enabledSubreddits = if (enabled) enabledSubreddits + sub else enabledSubreddits - sub
        settingsRepository.rebbitSubreddits = enabledSubreddits
    }

    fun setAllSubreddits(enabled: Boolean) {
        enabledSubreddits = if (enabled) allSubreddits.toSet() else emptySet()
        settingsRepository.rebbitSubreddits = enabledSubreddits
    }

    fun updateUserName(value: String) {
        userName = value
        settingsRepository.userName = value
    }

    fun updateUserBio(value: String) {
        userBio = value
        settingsRepository.userBio = value
    }

    private fun refreshSystemPrompts() {
        val presets = settingsRepository.loadSystemPrompts()
        systemPromptNames = presets.map { it.name }
        activeSystemPrompt = settingsRepository.activeSystemPromptName
        systemPromptText = presets.firstOrNull { it.name == activeSystemPrompt }?.text ?: ""
    }

    fun selectSystemPrompt(name: String) {
        settingsRepository.activeSystemPromptName = name
        refreshSystemPrompts()
    }

    fun updateSystemPromptText(value: String) {
        systemPromptText = value
        settingsRepository.setSystemPromptText(activeSystemPrompt, value)
    }

    fun addSystemPrompt() {
        settingsRepository.activeSystemPromptName = settingsRepository.addSystemPrompt()
        refreshSystemPrompts()
    }

    fun renameActiveSystemPrompt(newName: String) {
        settingsRepository.renameSystemPrompt(activeSystemPrompt, newName)
        refreshSystemPrompts()
    }

    fun deleteActiveSystemPrompt() {
        settingsRepository.deleteSystemPrompt(activeSystemPrompt)
        refreshSystemPrompts()
    }

    fun updateChatTemplate(value: String) {
        chatTemplate = value
        settingsRepository.chatTemplate = value
    }

    fun updateHistoryCap(value: String) {
        historyCap = value
        value.toIntOrNull()?.let { settingsRepository.historyCap = it }
    }

    fun updateMaxTokens(value: String) {
        maxTokens = value
        value.toIntOrNull()?.let { settingsRepository.maxTokens = it }
    }

    fun updateUseLocalDream(value: Boolean) {
        useLocalDream = value
        settingsRepository.useLocalDream = value
    }

    fun updateLocalDreamUrl(value: String) {
        localDreamUrl = value
        settingsRepository.localDreamUrl = value
    }

    fun updateLocalDreamScheduler(value: String) {
        localDreamScheduler = value
        settingsRepository.localDreamScheduler = value
    }

    fun updateForgeUrl(value: String) {
        forgeUrl = value
        settingsRepository.forgeUrl = value
    }

    // --- Backup / Restore ---
    var backupBusy by mutableStateOf(false)
        private set
    var backupStatus by mutableStateOf<String?>(null)
        private set

    fun suggestedBackupName(): String = com.mrj.fancyai.service.BackupService.suggestedFileName()

    fun exportBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "Backing up…"
        viewModelScope.launch {
            backupService.export(uri)
                .onSuccess { count -> backupStatus = "Backed up $count characters" }
                .onFailure { backupStatus = "Backup failed: ${it.message}" }
            backupBusy = false
        }
    }

    fun restoreBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "Restoring…"
        viewModelScope.launch {
            backupService.import(uri)
                .onSuccess { count ->
                    backupStatus = "Restored $count characters"
                    // Refresh settings-backed UI state from the restored values.
                    refreshSystemPrompts()
                    reloadFromSettings()
                }
                .onFailure { backupStatus = "Restore failed: ${it.message}" }
            backupBusy = false
        }
    }

    /** Re-reads the simple setting fields from the repository after a restore. */
    private fun reloadFromSettings() {
        llmProvider = settingsRepository.llmProvider
        themeMode = settingsRepository.themeMode
        nsfwEnabled = settingsRepository.nsfwEnabled
        userName = settingsRepository.userName
        userBio = settingsRepository.userBio
        autoPostEnabled = settingsRepository.autoPostEnabled
        socialPostIntervalMinutes = settingsRepository.socialPostIntervalMinutes.toString()
    }

    private fun applyHardwareParams() {
        val kv = settingsRepository.kvCacheType
        LlamaInference.setEngineParams(
            nCtx = settingsRepository.contextSize,
            nThreads = settingsRepository.threadCount,
            flashAttn = kv != 0, // flash attn required for a quantized KV cache
            useMmap = true,
            useMlock = false,
            gpuLayers = settingsRepository.gpuLayers,
            backend = settingsRepository.hardwareBackend,
            kvType = kv
        )
    }
}
