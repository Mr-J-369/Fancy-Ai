package com.mrj.fancyai.ui.shell

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.settings.GenerationTarget
import com.mrj.fancyai.ui.settings.LlmSettingsStore

internal const val HomePageCount = 2
internal const val RootPageIndex = 0
internal const val APP_PREFERENCES = "app"
internal const val KEY_INTRO_SEEN = "intro_seen"
internal const val KEY_RAM_MONITOR = "ram_monitor"
internal const val KEY_HF_MIRROR = "hf_mirror"

internal enum class HomeDestination(
    internal val blocksAutomaticWork: Boolean = true,
) {
    Home(blocksAutomaticWork = false),
    AutomaticPosts(blocksAutomaticWork = false),
    Cleanup,
    Settings,
    General,
    Backup,
    About,
    Diagnostics,
    ReplayIntro,
    Engines,
    Cloud,
    Generation,
    Instructions,
    Memory,
    Voice,
    Characters(blocksAutomaticWork = false),
    Aura,
    AuraSwap,
    AuraConverter,
    Vision,
    Terminal,
    Binder,
    Chat,
    RootChat,
    Phone,
    Rebbit(blocksAutomaticWork = false),
    Ustagram(blocksAutomaticWork = false),
    Y(blocksAutomaticWork = false),
    Dare(blocksAutomaticWork = false),
    FileManager,
    Gallery(blocksAutomaticWork = false),
    Games,
    Groups,
    RootCreator,
    RootProducer,
    Benchmark,
    Lorebook(blocksAutomaticWork = false),
    MemoryLibrary,
}

internal class HomeNavigationState(private val context: Context) {
    val destination: MutableState<HomeDestination> = mutableStateOf(HomeDestination.Home)
    val generationTarget: MutableState<GenerationTarget?> = mutableStateOf(null)
    val cloudProvider: MutableState<CloudProvider> = mutableStateOf(CloudProvider.DEEPINFRA)
    val engineRevision = mutableIntStateOf(0)
    var engineReturnHome = false
    var auraReturnDestination = HomeDestination.Home
    var benchmarkReturnDestination = HomeDestination.Home

    fun openCloud(provider: CloudProvider) {
        cloudProvider.value = provider
        destination.value = HomeDestination.Cloud
    }

    fun openEngineSetup(activeCloud: CloudProvider?, returnHome: Boolean = false) {
        if (returnHome) engineReturnHome = true
        if (activeCloud != null) openCloud(activeCloud) else destination.value = HomeDestination.Engines
    }

    fun openRuntimeSettings(next: HomeDestination, activeCloud: CloudProvider?) {
        generationTarget.value = LlmSettingsStore.generationTarget(context)
        if (generationTarget.value == null) openEngineSetup(activeCloud) else destination.value = next
    }

    fun openWithEngine(next: HomeDestination, engineName: String?, activeCloud: CloudProvider?) {
        if (engineName == null) {
            openEngineSetup(activeCloud)
        } else {
            destination.value = next
        }
    }

    fun backFromEngineSettings() {
        engineRevision.intValue++
        destination.value = if (engineReturnHome) HomeDestination.Home else HomeDestination.Settings
        engineReturnHome = false
    }
}
