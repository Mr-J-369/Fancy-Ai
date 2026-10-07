package com.mrj.fancyai.ui.shell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mrj.fancyai.service.InferenceForegroundService
import androidx.navigation3.runtime.entryProvider
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.aura.AuraConverterScreen
import com.mrj.fancyai.ui.aura.AuraScreen
import com.mrj.fancyai.ui.benchmark.BenchmarkScreen
import com.mrj.fancyai.ui.binder.BinderScreen
import com.mrj.fancyai.ui.characters.CharactersScreen
import com.mrj.fancyai.ui.characters.RootCreatorScreen
import com.mrj.fancyai.ui.characters.rootCharacter
import com.mrj.fancyai.ui.chat.ChatScreen
import com.mrj.fancyai.ui.cleanup.CleanupScreen
import com.mrj.fancyai.ui.faceswap.FaceSwapScreen
import com.mrj.fancyai.ui.files.FileManagerScreen
import com.mrj.fancyai.ui.gallery.GalleryScreen
import com.mrj.fancyai.ui.games.GamesScreen
import com.mrj.fancyai.ui.groups.GroupsScreen
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.lorebook.LorebookScreen
import com.mrj.fancyai.ui.memory.MemoryLibraryScreen
import com.mrj.fancyai.ui.music.RootProducerScreen
import com.mrj.fancyai.ui.phone.PhoneScreen
import com.mrj.fancyai.ui.rebbit.RebbitScreen
import com.mrj.fancyai.ui.settings.EngineStatusNames
import com.mrj.fancyai.ui.settings.SettingsScreen
import com.mrj.fancyai.ui.settings.engineStatusNames
import com.mrj.fancyai.ui.social.AutomaticPostsScreen
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.terminal.TerminalScreen
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.ustagram.UstagramScreen
import com.mrj.fancyai.ui.vision.VisionScreen
import com.mrj.fancyai.ui.y.YScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val appPreferences = context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
    var introSeen by rememberSaveable {
        mutableStateOf(appPreferences.getBoolean(KEY_INTRO_SEEN, false))
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            InferenceForegroundService.start(context)
        }
    }
    val navigation = remember(context) { HomeNavigationState(context) }
    var destination by navigation.destination
    val activity = context as? androidx.activity.ComponentActivity
    DisposableEffect(activity) {
        val handleIntent = { intent: Intent? ->
            if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
                destination = HomeDestination.Vision
            }
        }
        handleIntent(activity?.intent)
        val consumer = androidx.core.util.Consumer<Intent> { handleIntent(it) }
        activity?.addOnNewIntentListener(consumer)
        onDispose { activity?.removeOnNewIntentListener(consumer) }
    }
    val automaticState by AutomaticSocialPosts.state.collectAsState()
    val automaticBlocker = remember { Any() }
    val protectsInteractiveWork = destination.blocksAutomaticWork
    DisposableEffect(protectsInteractiveWork) {
        AutomaticSocialPosts.block(automaticBlocker, protectsInteractiveWork)
        onDispose { AutomaticSocialPosts.block(automaticBlocker, blocked = false) }
    }
    var exitRequested by rememberSaveable { mutableStateOf(value = false) }
    var engineLine by remember { mutableStateOf("") }
    var engineStatus by remember {
        mutableStateOf(
            EngineStatusNames(
                active = null,
                local = null,
                cloud = emptyMap(),
                activeCloud = null,
                generationTarget = null,
            )
        )
    }
    LaunchedEffect(navigation.engineRevision.intValue) {
        val (status, line) = withContext(Dispatchers.IO) {
            val s = engineStatusNames(context)
            s to homeEngineLine(context, s)
        }
        engineLine = line
        engineStatus = status
    }

    HomeDashboard(
        navigation = navigation,
        engineStatus = engineStatus,
        engineLine = engineLine,
        visible = introSeen && destination == HomeDestination.Home,
        onRequestExit = { exitRequested = true },
    )

    if (!introSeen) {
        BackHandler(enabled = true) { }
        IntroScreen {
            appPreferences.edit { putBoolean(KEY_INTRO_SEEN, true) }
            introSeen = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return
    }

    // Do not enter another engine's UI until the automatic job has released native resources.
    if (protectsInteractiveWork && automaticState.running) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.automatic_posts_finishing), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val entry = navigation.entries(context, engineStatus)(destination)
    key(entry.contentKey) {
        entry.Content()
    }

    if (exitRequested) {
        ExitConfirmationDialog(onDismiss = { exitRequested = false }, onExit = onExit)
    }
}

@Composable
private fun ExitConfirmationDialog(onDismiss: () -> Unit, onExit: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_exit_title)) },
        text = { Text(stringResource(R.string.app_exit_summary)) },
        confirmButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.action_exit), color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun HomeNavigationState.entries(context: Context, engineStatus: EngineStatusNames) =
    entryProvider {
        val onBack = { destination.value = HomeDestination.Home }
        entry(HomeDestination.Home) { }
        entry(HomeDestination.AutomaticPosts) { AutomaticPostsScreen(onBack) }
        entry(HomeDestination.Cleanup) { CleanupScreen(onBack) }
        listOf(
            HomeDestination.Settings, HomeDestination.Engines, HomeDestination.Cloud,
            HomeDestination.Generation, HomeDestination.Instructions, HomeDestination.Memory,
            HomeDestination.General, HomeDestination.About,
            HomeDestination.Backup, HomeDestination.Diagnostics, HomeDestination.ReplayIntro,
            HomeDestination.Voice,
        ).forEach { route ->
            // Keep the settings branch's composition identity when switching its pages.
            entry(route, contentKey = HomeDestination.Settings) { SettingsScreen(engineStatus) }
        }
        entry(HomeDestination.Characters) { CharactersScreen(onBack) }
        entry(HomeDestination.Aura) {
            AuraScreen(onBack = {
                destination.value = auraReturnDestination
                auraReturnDestination = HomeDestination.Home
            })
        }
        entry(HomeDestination.AuraConverter) {
            AuraConverterScreen(
                onBack = onBack,
                onOpenAura = {
                    auraReturnDestination = HomeDestination.AuraConverter
                    destination.value = HomeDestination.Aura
                },
            )
        }
        entry(HomeDestination.Vision) { VisionScreen(onBack) }
        entry(HomeDestination.AuraSwap) { FaceSwapScreen(onBack) }
        entry(HomeDestination.Terminal) { TerminalScreen(onBack) }
        entry(HomeDestination.Binder) { BinderScreen(onBack) }
        entry(HomeDestination.Chat) { ChatScreen(onBack = onBack) }
        entry(HomeDestination.RootChat) {
            ChatScreen(initialCharacter = rootCharacter(context), onBack = onBack)
        }
        entry(HomeDestination.Phone) { PhoneScreen(onBack) }
        entry(HomeDestination.Rebbit) { RebbitScreen(onBack) }
        entry(HomeDestination.Ustagram) { UstagramScreen(onBack) }
        entry(HomeDestination.Y) { YScreen(onBack) }
        entry(HomeDestination.Dare) { com.mrj.fancyai.ui.dare.DareScreen(onBack) }
        entry(HomeDestination.FileManager) { FileManagerScreen(onBack = onBack) }
        entry(HomeDestination.Gallery) { GalleryScreen(onBack) }
        entry(HomeDestination.Games) { GamesScreen(onBack) }
        entry(HomeDestination.Groups) { GroupsScreen(onBack) }
        entry(HomeDestination.Lorebook) { LorebookScreen(onBack) }
        entry(HomeDestination.MemoryLibrary) { MemoryLibraryScreen(onBack) }
        entry(HomeDestination.RootCreator) {
            RootCreatorScreen(onBack = onBack) { destination.value = HomeDestination.Characters }
        }
        entry(HomeDestination.RootProducer) { RootProducerScreen(onBack) }
        entry(HomeDestination.Benchmark) {
            BenchmarkScreen(onBack = {
                destination.value = benchmarkReturnDestination
                benchmarkReturnDestination = HomeDestination.Home
            })
        }
    }
