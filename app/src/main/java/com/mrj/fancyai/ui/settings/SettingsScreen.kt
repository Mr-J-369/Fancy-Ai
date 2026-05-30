package com.mrj.fancyai.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel
) {
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importModel(it) } }

    val backupExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val backupImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restoreBackup(it) } }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var pendingModelDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("System Settings", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Category: On-Device Model
            SettingsCategory(title = "On-Device Model", icon = Icons.Default.Star) {
                val loaded = viewModel.loadedModel
                Text(
                    text = if (loaded != null) "Loaded: $loaded" else "No model loaded",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (loaded != null) MaterialTheme.colorScheme.primary else Color.Gray
                )
                viewModel.modelStatus?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { modelPicker.launch(arrayOf("*/*")) },
                    enabled = !viewModel.isModelBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (viewModel.isModelBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Import .gguf Model")
                }

                if (viewModel.loadedModel != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.unloadModel() },
                        enabled = !viewModel.isModelBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unload Model (free memory)")
                    }
                }

                if (viewModel.availableModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Imported models", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    viewModel.availableModels.forEach { name ->
                        val isLoaded = name == viewModel.loadedModel
                        Surface(
                            onClick = { viewModel.loadModel(name) },
                            enabled = !viewModel.isModelBusy && !isLoaded,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = if (isLoaded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                if (isLoaded) {
                                    Icon(Icons.Default.Star, contentDescription = "Loaded",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { pendingModelDelete = name }, enabled = !viewModel.isModelBusy) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete $name",
                                        tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: AI Engine
            SettingsCategory(title = "AI Engine & Performance", icon = Icons.Default.Build) {
                SettingsDropdown(
                    label = "LLM Provider",
                    options = LLM_PROVIDERS,
                    current = viewModel.llmProvider,
                    onSelect = { viewModel.updateLlmProvider(it) }
                )

                if (viewModel.llmProvider == "llama") {
                    SettingsTextField(
                        label = "Model Path",
                        value = viewModel.modelPath,
                        onValueChange = { viewModel.updateModelPath(it) },
                        placeholder = "/sdcard/models/model.gguf"
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(
                        label = "Context",
                        value = viewModel.contextSize,
                        onValueChange = { viewModel.updateContextSize(it) },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        label = "Threads",
                        value = viewModel.threadCount,
                        onValueChange = { viewModel.updateThreadCount(it) },
                        modifier = Modifier.weight(1f)
                    )
                }

                SettingsHardwareToggle(
                    current = viewModel.hardwareBackend,
                    onSelect = { viewModel.updateHardwareBackend(it) }
                )

                SettingsSegmented(
                    label = "KV Cache Precision",
                    options = listOf("F16", "Q8", "Q4"),
                    current = viewModel.kvCacheType,
                    onSelect = { viewModel.updateKvCacheType(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Imaging Studio
            SettingsCategory(title = "Imaging Studio", icon = Icons.Default.Palette) {
                SettingsSwitch(
                    label = "Use Local Dream (NPU)",
                    description = "Generation runs on your device NPU",
                    checked = viewModel.useLocalDream,
                    onCheckedChange = { viewModel.updateUseLocalDream(it) }
                )

                if (viewModel.useLocalDream) {
                    SettingsTextField(
                        label = "Local Dream Server URL",
                        value = viewModel.localDreamUrl,
                        onValueChange = { viewModel.updateLocalDreamUrl(it) },
                        placeholder = "http://127.0.0.1:8081"
                    )
                    SettingsDropdown(
                        label = "Scheduler",
                        options = LOCAL_DREAM_SCHEDULERS,
                        current = viewModel.localDreamScheduler,
                        onSelect = { viewModel.updateLocalDreamScheduler(it) }
                    )
                } else {
                    SettingsTextField(
                        label = "Forge Endpoint URL",
                        value = viewModel.forgeUrl,
                        onValueChange = { viewModel.updateForgeUrl(it) },
                        placeholder = "http://127.0.0.1:7860"
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Dimensions, steps, CFG and denoising are set per-generation in the Imaging Studio.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Your Profile
            SettingsCategory(title = "Your Profile", icon = Icons.Default.Person) {
                SettingsTextField(
                    label = "Your Name",
                    value = viewModel.userName,
                    onValueChange = { viewModel.updateUserName(it) },
                    placeholder = "How characters address you"
                )
                SettingsTextField(
                    label = "About You",
                    value = viewModel.userBio,
                    onValueChange = { viewModel.updateUserBio(it) },
                    placeholder = "Background characters should know about you",
                    singleLine = false,
                    minLines = 2
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Global System Prompt (multiple named presets)
            SettingsCategory(title = "Global System Prompt", icon = Icons.Default.Edit) {
                SettingsDropdown(
                    label = "Active prompt",
                    options = viewModel.systemPromptNames.map { it to it },
                    current = viewModel.activeSystemPrompt,
                    onSelect = { viewModel.selectSystemPrompt(it) }
                )

                var nameEdit by remember(viewModel.activeSystemPrompt) { mutableStateOf(viewModel.activeSystemPrompt) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { viewModel.renameActiveSystemPrompt(nameEdit) },
                        enabled = nameEdit.isNotBlank() && nameEdit != viewModel.activeSystemPrompt
                    ) { Text("Rename") }
                }

                SettingsTextField(
                    label = "Prompt text",
                    value = viewModel.systemPromptText,
                    onValueChange = { viewModel.updateSystemPromptText(it) },
                    placeholder = "Global instructions applied to every character",
                    singleLine = false,
                    minLines = 4
                )

                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedButton(onClick = { viewModel.addSystemPrompt() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.deleteActiveSystemPrompt() },
                        enabled = viewModel.systemPromptNames.size > 1,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Intelligence
            SettingsCategory(title = "Character Intelligence", icon = Icons.Default.Face) {
                SettingsSwitch(
                    label = "Auto-Evolve Dossiers",
                    description = "Allow AI to summarize chats into memory blocks",
                    checked = viewModel.autoEvolveDossier,
                    onCheckedChange = { viewModel.updateAutoEvolveDossier(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Autonomous Posting
            val appContext = LocalContext.current
            SettingsCategory(title = "Autonomous Posting", icon = Icons.Default.Refresh) {
                SettingsSwitch(
                    label = "Auto-post to social feeds",
                    description = "Characters post on their own on a schedule",
                    checked = viewModel.autoPostEnabled,
                    onCheckedChange = {
                        viewModel.updateAutoPostEnabled(it)
                        com.mrj.fancyai.service.SocialScheduler.apply(
                            appContext, it, viewModel.socialPostIntervalMinutes.toIntOrNull() ?: 240
                        )
                    }
                )
                SettingsTextField(
                    label = "Interval (minutes)",
                    value = viewModel.socialPostIntervalMinutes,
                    onValueChange = {
                        viewModel.updateSocialPostIntervalMinutes(it)
                        if (viewModel.autoPostEnabled) {
                            it.toIntOrNull()?.let { min ->
                                com.mrj.fancyai.service.SocialScheduler.apply(appContext, true, min)
                            }
                        }
                    },
                    placeholder = "e.g. 60"
                )
                Text(
                    "Background posting runs at most every ~15 min (system limit). Shorter values are clamped.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Connectivity
            SettingsCategory(title = "Cloud & API", icon = Icons.Default.Info) {
                when (viewModel.llmProvider) {
                    "deepinfra", "openrouter" -> {
                        SettingsTextField(
                            label = "API Key",
                            value = viewModel.apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            placeholder = "sk-..."
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { viewModel.fetchModels() },
                            enabled = !viewModel.isFetchingModels,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (viewModel.isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Fetch available models")
                        }
                        viewModel.modelFetchError?.let {
                            Text(it, color = Color.Red, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                        }

                        if (viewModel.fetchedModels.isNotEmpty()) {
                            SettingsSearchableDropdown(
                                label = "Model (${viewModel.fetchedModels.size} available)",
                                options = viewModel.fetchedModels,
                                current = viewModel.cloudModel,
                                onSelect = { viewModel.updateCloudModel(it) }
                            )
                        } else {
                            SettingsTextField(
                                label = "Model",
                                value = viewModel.cloudModel,
                                onValueChange = { viewModel.updateCloudModel(it) },
                                placeholder = "meta-llama/Llama-3-70b-chat"
                            )
                        }
                    }

                    "localllm", "custom" -> {
                        SettingsTextField(
                            label = "Endpoint URL",
                            value = viewModel.customBackendUrl,
                            onValueChange = { viewModel.updateCustomBackendUrl(it) },
                            placeholder = if (viewModel.llmProvider == "localllm") "http://127.0.0.1:8082" else "http://10.0.2.2:5000"
                        )
                        SettingsTextField(
                            label = "Model",
                            value = viewModel.cloudModel,
                            onValueChange = { viewModel.updateCloudModel(it) },
                            placeholder = "model name as served"
                        )
                    }

                    else -> {
                        Text(
                            "Using the on-device model — no cloud configuration needed. Switch the LLM Provider above to use a cloud or HTTP backend.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Appearance (app theme)
            SettingsCategory(title = "Appearance", icon = Icons.Default.Settings) {
                val themeOptions = listOf("System", "Light", "Dark")
                val themeKeys = listOf("system", "light", "dark")
                SettingsSegmented(
                    label = "Theme",
                    options = themeOptions,
                    current = themeKeys.indexOf(viewModel.themeMode).coerceAtLeast(0),
                    onSelect = { viewModel.updateThemeMode(themeKeys[it]) }
                )
                Text(
                    "\"System\" follows your phone's light/dark setting.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Content
            var showSubredditDialog by remember { mutableStateOf(false) }
            SettingsCategory(title = "Content", icon = Icons.Default.Lock) {
                SettingsSwitch(
                    label = "NSFW content",
                    description = "Show the Rebbit app and include it in autonomous posting.",
                    checked = viewModel.nsfwEnabled,
                    onCheckedChange = { viewModel.updateNsfwEnabled(it) }
                )
                if (viewModel.nsfwEnabled) {
                    OutlinedButton(
                        onClick = { showSubredditDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Subreddits (${viewModel.enabledSubreddits.size}/${viewModel.allSubreddits.size})")
                    }
                }
            }
            if (showSubredditDialog) {
                SubredditPickerDialog(
                    all = viewModel.allSubreddits,
                    enabled = viewModel.enabledSubreddits,
                    onToggle = { sub, on -> viewModel.toggleSubreddit(sub, on) },
                    onAll = { viewModel.setAllSubreddits(true) },
                    onNone = { viewModel.setAllSubreddits(false) },
                    onDismiss = { showSubredditDialog = false }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Aesthetics
            SettingsCategory(title = "Virtual OS Appearance", icon = Icons.Default.Settings) {
                SettingsTextField(
                    label = "Wallpaper Mode",
                    value = viewModel.wallpaperMode,
                    onValueChange = { viewModel.updateWallpaperMode(it) },
                    placeholder = "mesh / solid / deep"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: Backup & Restore
            SettingsCategory(title = "Backup & Restore", icon = Icons.Default.Refresh) {
                Text(
                    "Export everything (characters, chats, memories, social, images, settings) to a .zip, or restore from one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                viewModel.backupStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(
                        onClick = { backupExporter.launch(viewModel.suggestedBackupName()) },
                        enabled = !viewModel.backupBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (viewModel.backupBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back up")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showRestoreConfirm = true },
                        enabled = !viewModel.backupBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category: About & Support
            val ctx = LocalContext.current
            fun openUrl(url: String) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            }
            SettingsCategory(title = "About & Support", icon = Icons.Default.Info) {
                Text("Fancy AI · v4.0.0", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openUrl("https://github.com/Mr-J-369/Fancy-Ai/issues") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Report a bug (GitHub)") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openUrl("https://t.me/Fancy_AI_MrJ") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Telegram / Updates") }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Thanks & credits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Fancy AI stands on amazing open-source work. Thank you to:\n" +
                        "• llama.cpp — on-device LLM engine\n" +
                        "• Local Dream — on-device NPU image generation (github.com/xororz/local-dream)\n" +
                        "• Stable Diffusion WebUI Forge / AUTOMATIC1111 — image backends\n" +
                        "• DeepInfra & OpenRouter — cloud LLM providers\n" +
                        "• Jetpack Compose, Room, WorkManager, CameraX, ML Kit (AndroidX)\n" +
                        "• Retrofit, OkHttp, Gson, Coil, Kotlin & coroutines\n" +
                        "See the NOTICE file for full license attributions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    pendingModelDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingModelDelete = null },
            title = { Text("Delete model?") },
            text = { Text("This permanently removes \"$name\" from device storage. You'd need to re-import the .gguf to use it again.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteModel(name); pendingModelDelete = null }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = { TextButton(onClick = { pendingModelDelete = null }) { Text("Cancel") } }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces ALL current characters, chats, images and settings with the contents of the backup. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    backupImporter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) { Text("Choose file", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubredditPickerDialog(
    all: List<String>,
    enabled: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val shown = all.filter { it.contains(filter.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subreddits") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    TextButton(onClick = onAll) { Text("All") }
                    TextButton(onClick = onNone) { Text("None") }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(shown) { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(sub, sub !in enabled) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = sub in enabled, onCheckedChange = { onToggle(sub, it) })
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(sub, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun SettingsCategory(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = singleLine,
            minLines = minLines
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdown(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            OutlinedTextField(
                value = currentLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSearchableDropdown(
    label: String,
    options: List<String>,
    current: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Surface(
            onClick = { open = true; query = "" },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    current.ifBlank { "Select a model" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current.isBlank()) Color.Gray else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search models…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val filtered = remember(query, options) {
                        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
                    }
                    Text("${filtered.size} of ${options.size}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered) { opt ->
                            Surface(
                                onClick = { onSelect(opt); open = false },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                color = if (opt == current) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(opt, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsSegmented(label: String, options: List<String>, current: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = current == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)) else null
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsHardwareToggle(current: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Inference Hardware", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("CPU", "NPU", "GPU").forEachIndexed { index, label ->
                val isSelected = current == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)) else null
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
