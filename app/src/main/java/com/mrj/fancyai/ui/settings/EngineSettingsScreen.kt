package com.mrj.fancyai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LlamaBackend
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.service.llm.LlamaOffload
import com.mrj.fancyai.ui.benchmark.BenchmarkStore
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun EngineSettingsScreen(
    onBack: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onSelectionChanged: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { EngineSettingsController(context, scope) }
    var modelsExpanded by rememberSaveable { mutableStateOf(value = false) }
    var showBrowseModels by rememberSaveable { mutableStateOf(value = false) }
    with(controller) {
        LaunchedEffect(refresh) { loadModels(onSelectionChanged) }
        LaunchedEffect(selectedPath) {
            if (selectedPath != null) preferences = EnginePreferences(context)
        }
        val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && importingName == null) importModel(uri, onSelectionChanged)
        }

        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            AppHeader(title = stringResource(R.string.engines_title), onBack = onBack, subtitle = stringResource(R.string.engines_subtitle))
            importingName?.let { ImportProgressBanner(it, importProgress) { stopDownload() } }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                modelLibrary(
                    controller = controller,
                    modelsExpanded = modelsExpanded,
                    onExpanded = { modelsExpanded = it },
                    onSelectionChanged = onSelectionChanged,
                    onImport = { importModel.launch(arrayOf("*/*")) },
                    onBenchmark = {
                        selectedPath?.let { path -> BenchmarkStore(context).setSelectedModelPath(path) }
                        onOpenBenchmark()
                    },
                    onBrowseModels = { showBrowseModels = true },
                )
                if (models.isNotEmpty()) {
                    backendSettings(preferences, selectedModel?.runtime)
                    contextSettings(preferences)
                    threadSettings(preferences, selectedModel?.runtime)
                    cacheSettings(preferences, selectedModel, liteRtGreedySampling)

                }
                if (errorMessage != 0) {
                    item {
                        Text(
                            text = stringResource(errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        pendingRemoval?.let { model ->
            AppDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text(stringResource(R.string.remove_item_title, model.name)) },
                text = { Text(stringResource(R.string.engines_remove_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRemoval = null
                            remove(model)
                        },
                    ) {
                        Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) {
                        Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }

        if (showBrowseModels) {
            BrowseModelsDialog(
                models = models,
                importingName = importingName,
                importProgress = importProgress,
                onDismiss = { showBrowseModels = false },
                onDownload = { starter -> downloadStarter(starter, onSelectionChanged) },
                onStop = { stopDownload() },
            )
        }
    }
}

private fun LazyListScope.modelLibrary(
    controller: EngineSettingsController,
    modelsExpanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onSelectionChanged: () -> Unit,
    onImport: () -> Unit,
    onBenchmark: () -> Unit,
    onBrowseModels: () -> Unit,
) {
    with(controller) {
        item {
            Text(
                text = stringResource(R.string.section_installed_models),
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (models.isEmpty()) {
            item { EmptyModels() }
        } else {
            item {
                ModelPicker(
                    models = models,
                    selectedPath = selectedPath,
                    active = localActive,
                    expanded = modelsExpanded,
                    onToggle = { onExpanded(!modelsExpanded) },
                    onSelect = { model ->
                        onExpanded(false)
                        selectedPath = model.path
                        LlmSettingsStore.selectModel(context, model.path)
                        localActive = true
                        onSelectionChanged()
                    },
                ) { pendingRemoval = it }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (models.isNotEmpty()) Arrangement.SpaceBetween else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.engines_browse_models),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onBrowseModels)
                        .padding(horizontal = 8.dp, vertical = 15.dp),
                )
                if (models.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.engines_reset_defaults),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                LlmSettingsStore.resetSelectedEngine(context)
                                preferences = EnginePreferences(context)
                            }
                            .padding(horizontal = 8.dp, vertical = 15.dp),
                    )
                }
            }
        }
        item {
            ImportModelRow(enabled = importingName == null) { onImport() }
        }
        item {
            BenchmarkShortcutRow(enabled = importingName == null, onClick = onBenchmark)
        }
    }
}

private fun LazyListScope.backendSettings(settings: EnginePreferences, runtime: LocalLlmRuntime?) {
    with(settings) {
        if (runtime == LocalLlmRuntime.LITERT) {
            item {
                RadioChoiceGroup(
                    sectionTitleRes = R.string.engines_litert_backend,
                    options = listOf(
                        Triple(LiteRtBackend.CPU, stringResource(R.string.engines_cpu), stringResource(R.string.engines_litert_cpu_summary)),
                        Triple(LiteRtBackend.GPU, stringResource(R.string.engines_gpu), stringResource(R.string.engines_litert_gpu_summary)),
                    ),
                    selected = liteRtBackend,
                ) { choice ->
                    liteRtBackend = choice
                    LlmSettingsStore.saveLiteRtBackend(context, choice)
                }
            }
        }
        if (runtime == LocalLlmRuntime.LLAMA) {
            item {
                RadioChoiceGroup(
                    sectionTitleRes = R.string.engines_llama_backend,
                    options = listOf(
                        Triple(LlamaBackend.CPU, stringResource(R.string.engines_cpu), stringResource(R.string.engines_llama_cpu_summary)),
                        Triple(LlamaBackend.OPENCL, stringResource(R.string.engines_opencl), stringResource(R.string.engines_opencl_summary)),
                        Triple(LlamaBackend.HEXAGON, stringResource(R.string.engines_hexagon), stringResource(R.string.engines_hexagon_summary)),
                    ),
                    selected = llamaBackend,
                ) { backend ->
                    llamaBackend = backend
                    LlmSettingsStore.saveLlamaBackend(context, backend)
                    llamaOffloadLayers = LlmSettingsStore.llamaOffloadLayers(context)
                }
            }
            if (llamaBackend != LlamaBackend.CPU) {
                item {
                    val layers = llamaOffloadLayers
                    val customLayers = layers.takeIf { it >= 0 } ?: DEFAULT_CUSTOM_OFFLOAD_LAYERS
                    val updateLayers: (Int) -> Unit = { value ->
                        llamaOffloadLayers = value
                        LlmSettingsStore.saveLlamaOffloadLayers(context, value)
                    }
                    Column {
                        RadioChoiceGroup(
                            sectionTitleRes = R.string.engines_accelerator_layers,
                            options = listOf(
                                Triple(LlamaOffload.AUTOMATIC.layers, stringResource(R.string.engines_offload_automatic), stringResource(R.string.engines_offload_automatic_summary)),
                                Triple(LlamaOffload.ALL.layers, stringResource(R.string.engines_offload_all), stringResource(R.string.engines_offload_all_summary)),
                                Triple(customLayers, stringResource(R.string.engines_offload_exact), stringResource(R.string.engines_offload_exact_summary)),
                            ),
                            selected = layers,
                            onSelect = updateLayers,
                        )
                        if (layers >= 0) {
                            SettingsStepper(
                                title = stringResource(R.string.engines_offload_exact_layers),
                                value = pluralStringResource(R.plurals.settings_layers, layers, layers),
                                summary = stringResource(R.string.engines_offload_exact_layers_summary),
                                onLower = (layers - 1).takeIf { layers > 0 }?.let { { updateLayers(it) } },
                                onHigher = { updateLayers(layers + 1) },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.contextSettings(settings: EnginePreferences) {
    with(settings) {
        val updateContext: (Int) -> Unit = { tokens ->
            contextTokens = tokens
            LlmSettingsStore.saveContextTokens(context, tokens)
        }
        item {
            val ladder = LlmSettingsStore.contextLadder
            SettingsStepper(
                title = stringResource(R.string.engines_context),
                value = pluralStringResource(R.plurals.settings_tokens, contextTokens, contextTokens),
                summary = stringResource(R.string.engines_context_override_summary),
                onLower = ladder.below(contextTokens)?.let { { updateContext(it) } },
                onHigher = ladder.above(contextTokens)?.let { { updateContext(it) } },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private fun LazyListScope.threadSettings(settings: EnginePreferences, runtime: LocalLlmRuntime?) {
    with(settings) {
        val llama = runtime == LocalLlmRuntime.LLAMA
        if (llama) {
            item {
                Text(
                    text = stringResource(R.string.engines_threading),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        if (llama || (runtime == LocalLlmRuntime.LITERT && liteRtBackend == LiteRtBackend.CPU)) {
            item {
                CpuThreadSetting(cpuThreads, Runtime.getRuntime().availableProcessors().coerceAtLeast(1), llama) {
                    cpuThreads = it
                    LlmSettingsStore.saveCpuThreads(context, it, llama = llama)
                }
            }
        }
        if (llama) {
            item {
                SettingsStepper(
                    title = stringResource(R.string.engines_prefill_threads),
                    value = if (promptThreads == 0) "0" else pluralStringResource(R.plurals.settings_threads, promptThreads, promptThreads),
                    summary = stringResource(R.string.engines_prefill_threads_summary),
                    onLower = if (promptThreads > 0) ({ promptThreads--; LlmSettingsStore.savePromptThreads(context, promptThreads) }) else null,
                    onHigher = { promptThreads++; LlmSettingsStore.savePromptThreads(context, promptThreads) },
                )
            }
            item {
                Text(text = stringResource(R.string.engines_llama_runtime), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
            }
            listOf(
                Triple(R.string.engines_batch, batchTokens) { value: Int -> batchTokens = value; LlmSettingsStore.saveBatchTokens(context, value) },
                Triple(R.string.engines_micro_batch, microBatchTokens) { value: Int -> microBatchTokens = value; LlmSettingsStore.saveMicroBatchTokens(context, value) },
            ).forEach { (label, value, update) ->
                item(key = label) {
                    EngineTokenInput(label, value, minimum = if (label == R.string.engines_micro_batch) 0 else 1, onChange = update)
                }
            }
        }
    }
}

private fun LazyListScope.cacheSettings(settings: EnginePreferences, selectedModel: LocalLlmModel?, liteRtGreedySampling: Boolean) {
    with(settings) {
        if (selectedModel?.runtime == LocalLlmRuntime.LLAMA) {
            item {
                RadioChoiceGroup(
                    sectionTitleText = stringResource(R.string.engine_flash_attention),
                    options = listOf(
                        Triple(null, stringResource(R.string.aura_processor_auto), stringResource(R.string.engines_flash_attention_auto_summary)),
                        Triple(true, stringResource(R.string.settings_on), stringResource(R.string.engines_flash_attention_summary)),
                        Triple(false, stringResource(R.string.settings_off), stringResource(R.string.engines_flash_attention_off_summary)),
                    ),
                    selected = flashAttention,
                ) { enabled ->
                    flashAttention = enabled
                    LlmSettingsStore.saveFlashAttention(context, enabled)
                }
            }
            listOf(R.string.engines_cache_type_k, R.string.engines_cache_type_v).forEach { label ->
                item(key = label) {
                    var expanded by remember { mutableStateOf(false) }
                    val key = label == R.string.engines_cache_type_k
                    val choices = LlmSettingsStore.cacheTypes.map { it to it.uppercase() }
                    val selected = if (key) cacheTypeK else cacheTypeV
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = true },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(choices.first { it.first == selected }.second, style = MaterialTheme.typography.titleMedium, color = Accent)
                            Icon(painter = painterResource(R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.padding(start = 12.dp).size(22.dp))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            choices.forEach { (value, title) ->
                                DropdownMenuItem(text = { Text(title, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        if (key) cacheTypeK = value else cacheTypeV = value
                                        LlmSettingsStore.saveCacheType(context, key = key, type = value)
                                        expanded = false
                                    }, trailingIcon = if (value == selected) { { Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp)) } } else null)
                            }
                        }
                    }
                }
            }
        }
        if (selectedModel?.runtime == LocalLlmRuntime.LLAMA) {
            item {
                CompactSwitchRow(stringResource(R.string.engines_mmap), useMmap, {
                    useMmap = it
                    LlmSettingsStore.saveUseMmap(context, it)
                }, summary = stringResource(R.string.engines_mmap_summary))
            }
        }
        if (selectedModel?.runtime == LocalLlmRuntime.LLAMA) {
            item {
                CompactSwitchRow(
                    title = stringResource(R.string.engines_cpu_repack),
                    summary = stringResource(R.string.engines_cpu_repack_summary),
                    checked = cpuRepack,
                    onCheckedChange = { enabled ->
                        cpuRepack = enabled
                        LlmSettingsStore.saveCpuRepack(context, enabled)
                    },
                )
            }
        }
        selectedModel
            ?.takeIf(LocalLlmModel::supportsSpeculativeDecoding)
            ?.let { model ->
                val available = (model.runtime != LocalLlmRuntime.LITERT) || liteRtGreedySampling
                item {
                    CompactSwitchRow(
                        title = stringResource(R.string.engine_speculative_decoding),
                        summary = stringResource(
                            if (available) R.string.engines_speculative_decoding_summary
                            else R.string.engines_speculative_sampling_required,
                        ),
                        checked = speculativeDecoding && available,
                        enabled = available,
                        modifier = Modifier.padding(top = 8.dp),
                        onCheckedChange = { enabled ->
                            speculativeDecoding = enabled
                            LlmSettingsStore.saveSpeculativeDecoding(context, enabled)
                        },
                    )
                }
            }
    }
}

@Composable
private fun EngineTokenInput(label: Int, value: Int, minimum: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        PostInput(value = text, onValueChange = { input ->
            text = input
            input.toIntOrNull()?.takeIf { it >= minimum }?.let(onChange)
        }, hint = stringResource(label), singleLine = true, modifier = Modifier.width(100.dp))
    }
}
