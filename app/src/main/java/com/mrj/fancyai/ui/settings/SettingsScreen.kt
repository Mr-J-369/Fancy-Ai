package com.mrj.fancyai.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.shell.HomeDestination
import com.mrj.fancyai.ui.shell.HomeNavigationState
import com.mrj.fancyai.ui.shell.IntroScreen
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun HomeNavigationState.SettingsScreen(engineStatus: EngineStatusNames) {
    when (destination.value) {
        HomeDestination.Settings -> {
            BackHandler { destination.value = HomeDestination.Home }
            val context = LocalContext.current
            Column(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.surfaceContainerLow,
                            0.28f to Ink,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp),
            ) {
                AppHeader(
                    title = stringResource(R.string.settings_title),
                    onBack = { destination.value = HomeDestination.Home },
                    subtitle = stringResource(R.string.settings_subtitle),
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                ) {
                    item {
                        EngineSelectionSection(engineStatus, context)
                        ConversationSettings(engineStatus)
                    }
                    item { MainSettingsMenu() }
                }
            }
        }
        HomeDestination.Engines -> EngineSettingsScreen(
            onBack = ::backFromEngineSettings,
            onOpenBenchmark = {
                benchmarkReturnDestination = HomeDestination.Engines
                destination.value = HomeDestination.Benchmark
            },
            onSelectionChanged = { engineRevision.intValue++ },
        )
        HomeDestination.Cloud -> CloudSettingsScreen(cloudProvider.value, ::backFromEngineSettings) {
            engineRevision.intValue++
        }
        HomeDestination.Generation -> GenerationScreen(checkNotNull(generationTarget.value)) {
            destination.value = HomeDestination.Settings
        }
        HomeDestination.Instructions -> InstructionsScreen { destination.value = HomeDestination.Settings }
        HomeDestination.Memory -> MemoryScreen(checkNotNull(generationTarget.value)) {
            destination.value = HomeDestination.Settings
        }
        HomeDestination.General -> GeneralSettingsScreen(
            onReplayIntro = { destination.value = HomeDestination.ReplayIntro },
            onBack = { destination.value = HomeDestination.Settings },
        )
        HomeDestination.About -> AboutScreen { destination.value = HomeDestination.Settings }
        HomeDestination.Diagnostics -> DiagnosticsScreen { destination.value = HomeDestination.Settings }
        HomeDestination.Backup -> BackupScreen {
            destination.value = HomeDestination.Settings
        }
        HomeDestination.Voice -> VoiceSettingsScreen { destination.value = HomeDestination.Settings }
        HomeDestination.ReplayIntro -> {
            BackHandler { destination.value = HomeDestination.General }
            IntroScreen { destination.value = HomeDestination.General }
        }
        else -> Unit
    }
}

@Composable
private fun HomeNavigationState.ConversationSettings(engineStatus: EngineStatusNames) {
    val target = engineStatus.generationTarget
    val generationTitle = target?.let {
        val runtimeName = if (it == GenerationTarget.CLOUD && engineStatus.activeCloud != null) {
            cloudProviderName(engineStatus.activeCloud)
        } else {
            generationRuntimeName(it)
        }
        stringResource(R.string.generation_runtime_title, stringResource(runtimeName))
    } ?: stringResource(R.string.generation_title)
    val generationSummary = target?.let { stringResource(generationRuntimeSubtitle(it)) }
        ?: stringResource(R.string.settings_generation_unconfigured)

    Text(stringResource(R.string.settings_conversation), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        title = generationTitle,
        summary = generationSummary,
        status = if (target == null) stringResource(R.string.settings_not_configured) else null,
        onClick = {
            openRuntimeSettings(HomeDestination.Generation, engineStatus.activeCloud)
        },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.instructions_assistant_title),
        stringResource(R.string.settings_instructions_summary),
        onClick = { destination.value = HomeDestination.Instructions },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.memory_title),
        stringResource(R.string.settings_memory_summary),
        onClick = { openRuntimeSettings(HomeDestination.Memory, engineStatus.activeCloud) },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.settings_voice), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.voice_title),
        stringResource(R.string.settings_voice_summary),
        status = stringResource(R.string.voice_android),
        onClick = { destination.value = HomeDestination.Voice },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun HomeNavigationState.EngineSelectionSection(status: EngineStatusNames, context: Context) {
    Text(stringResource(R.string.settings_active_engine), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    val localActive = status.activeCloud == null && status.active != null
    SettingsRow(
        title = stringResource(R.string.settings_this_device),
        summary = status.local ?: stringResource(R.string.engines_no_models),
        onClick = {
            if (localActive) destination.value = HomeDestination.Engines
            else if (LlmSettingsStore.activateLocal(context)) engineRevision.intValue++
            else destination.value = HomeDestination.Engines
        },
        action = stringResource(if (localActive) R.string.status_active else if (status.local != null) R.string.settings_use_engine else R.string.settings_set_up),
        active = localActive,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    CloudProvider.entries.forEach { provider ->
        val active = status.activeCloud == provider && status.cloud[provider] != null && status.active != null
        SettingsRow(
            title = stringResource(cloudProviderName(provider)),
            summary = status.cloud[provider] ?: stringResource(R.string.settings_not_configured),
            onClick = {
                if (active) openCloud(provider)
                else if (CloudSettingsStore.activateCloud(context, provider)) engineRevision.intValue++
                else openCloud(provider)
            },
            action = stringResource(if (active) R.string.status_active else if (status.cloud[provider] != null) R.string.settings_use_engine else R.string.settings_set_up),
            active = active,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
    Text(
        stringResource(R.string.settings_engine_switch_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
    )
}

@Composable
private fun HomeNavigationState.MainSettingsMenu() {
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.settings_general), stringResource(R.string.settings_general_summary),
        onClick = { destination.value = HomeDestination.General },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.backup_title), stringResource(R.string.backup_subtitle),
        onClick = { destination.value = HomeDestination.Backup },
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.diagnostics_title), stringResource(R.string.diagnostics_summary),
        onClick = { destination.value = HomeDestination.Diagnostics },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SettingsRow(
        stringResource(R.string.settings_about), stringResource(R.string.settings_about_summary),
        onClick = { destination.value = HomeDestination.About },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}
