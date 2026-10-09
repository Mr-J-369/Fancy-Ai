package com.mrj.fancyai.ui.settings

import android.app.LocaleConfig
import android.app.LocaleManager
import android.os.LocaleList
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.R
import com.mrj.fancyai.backup.BackupSchedule
import com.mrj.fancyai.backup.ContentBackup
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitch
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.shell.APP_PREFERENCES
import com.mrj.fancyai.ui.shell.KEY_HF_MIRROR
import com.mrj.fancyai.ui.shell.KEY_RAM_MONITOR
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            AppHeader(title = stringResource(R.string.settings_about), onBack = onBack, subtitle = stringResource(R.string.settings_about_summary))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                AboutLinks.forEach { link ->
                    SettingsRow(
                        title = stringResource(link.title),
                        summary = stringResource(link.summary),
                        onClick = {
                            val uri = link.url.toUri()
                            try {
                                context.startActivity(Intent(if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar(resources.getString(R.string.about_link_failed)) }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private data class AboutLink(@param:StringRes val title: Int, @param:StringRes val summary: Int, val url: String)

private val AboutLinks = listOf(
    AboutLink(R.string.about_sponsor, R.string.about_sponsor_summary, "https://ko-fi.com/mrj369"),
    AboutLink(R.string.about_privacy, R.string.about_privacy_summary, "https://huggingface.co/Mr-J-369/Fancy-AI/blob/main/PRIVACY.md"),
    AboutLink(R.string.about_changelog, R.string.about_changelog_summary, "https://fancyai-os.com/changelog"),
    AboutLink(R.string.about_email, R.string.about_email_address, "mailto:support@fancyai-os.com"),
    AboutLink(R.string.about_telegram, R.string.about_telegram_summary, "https://t.me/Fancy_Ai_Chat"),
    AboutLink(R.string.about_issues, R.string.about_issues_summary, "https://github.com/Mr-J-369/Fancy-Ai/issues"),
    AboutLink(R.string.about_website, R.string.about_website_summary, "https://fancyai-os.com"),
)

@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val currentResources by rememberUpdatedState(resources)
    val controller = remember(context, scope) {
        DiagnosticsController(context, scope) { message -> snackbar.showSnackbar(currentResources.getString(message)) }
    }
    var selected by rememberSaveable { mutableStateOf<DiagnosticReport?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    fun back() { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = ::back)
    LaunchedEffect(selected, revision) {
        controller.load(selected)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) controller.save(uri)
    }
    Box(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Column(Modifier.fillMaxSize()) {
            AppHeader(
                title = stringResource(selected?.title ?: R.string.diagnostics_title),
                subtitle = stringResource(selected?.summary ?: R.string.diagnostics_summary),
                onBack = ::back,
            )
            val report = selected
            if (report == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(24.dp))
                    val loggingTitle = stringResource(R.string.diagnostics_logging)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loggingTitle, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.diagnostics_logging_summary), style = MaterialTheme.typography.bodySmall)
                        }
                        CompactSwitch(
                            checked = controller.logging,
                            enabled = !controller.exporting,
                            label = loggingTitle,
                            onCheckedChange = controller::setLoggingEnabled,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    DiagnosticReport.entries.forEach { report ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        SettingsRow(stringResource(report.title), stringResource(report.summary), onClick = { selected = report })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            } else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !controller.exporting, onClick = { revision++ }) { Text(stringResource(R.string.files_refresh)) }
                    TextButton(enabled = controller.text != null && !controller.exporting, onClick = { save.launch(report.filename) }) {
                        Text(stringResource(R.string.action_save))
                    }
                    TextButton(enabled = controller.text != null && !controller.exporting, onClick = {
                        controller.share(report, controller.text)
                    }) { Text(stringResource(R.string.action_share)) }
                    TextButton(enabled = !controller.exporting && !controller.deleted, onClick = {
                        controller.delete(report)
                    }) { Text(stringResource(R.string.action_delete)) }
                }
                when {
                    controller.deleted -> Text(stringResource(R.string.diagnostics_deleted), style = MaterialTheme.typography.bodySmall)
                    controller.text == null -> CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp))
                    else -> {
                        val lines = remember(controller.text) { controller.text.orEmpty().lines() }
                        SelectionContainer(Modifier.weight(1f)) {
                            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                                itemsIndexed(lines) { _, line ->
                                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun InstructionsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var assistantInstruction by remember(context) { mutableStateOf(readAssistantInstruction(context)) }
    val preferences = remember(context) { context.getSharedPreferences("assistant_protocol", Context.MODE_PRIVATE) }
    val defaultImageInstruction = stringResource(R.string.instructions_requested_image_default)
    val defaultImageTriggers = stringResource(R.string.instructions_image_triggers_default)
    var requestedImageInstruction by remember(preferences, defaultImageInstruction) { mutableStateOf(preferences.getString("requested_image_instruction", defaultImageInstruction).orEmpty()) }
    var imageTriggers by remember(preferences, defaultImageTriggers) { mutableStateOf(preferences.getString("image_triggers", defaultImageTriggers).orEmpty()) }


    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).windowInsetsPadding(WindowInsets.ime).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.instructions_assistant_title), onBack = onBack, subtitle = null)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.instructions_assistant_title).uppercase(), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.instructions_assistant_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                PostInput(
                    value = assistantInstruction, hint = stringResource(R.string.instructions_hint),
                    minLines = 2, maxLines = 6,
                    modifier = Modifier.padding(top = 10.dp),
                    onValueChange = { value -> assistantInstruction = value; saveAssistantInstruction(context, value) },
                )
            }
            item {
                Text(stringResource(R.string.instructions_requested_image), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp))
                PostInput(value = requestedImageInstruction, hint = stringResource(R.string.instructions_requested_image),
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 96.dp, max = 200.dp),
                    onValueChange = { requestedImageInstruction = it; preferences.edit { putString("requested_image_instruction", it) } })
                TextButton(onClick = {
                    requestedImageInstruction = defaultImageInstruction
                    preferences.edit { remove("requested_image_instruction") }
                }) { Text(stringResource(R.string.action_reset_prompt)) }
            }
            item {
                Text(stringResource(R.string.instructions_image_triggers), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp))
                Text(stringResource(R.string.instructions_image_triggers_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PostInput(value = imageTriggers, hint = stringResource(R.string.instructions_image_triggers),
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 96.dp, max = 200.dp),
                    onValueChange = { imageTriggers = it; preferences.edit { putString("image_triggers", it) } })
            }


        }
    }
}

@Composable
internal fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { app.getSharedPreferences("content_backup", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableIntStateOf(0) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = remember(revision) { prefs.getBoolean("enabled", false) }
    val folder = remember(revision) { prefs.getString("folder", null) }
    val last = remember(revision) { prefs.getLong("last_automatic", 0) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = 0
            try {
                ContentBackup.lock.withLock { ContentBackup.write(app, uri) }
                message = R.string.backup_saved
            } finally { busy = false }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = 0
            try {
                val created = withContext(Dispatchers.IO) {
                    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    BackupSchedule.configure(app, uri, true)
                    BackupSchedule.automatic(app)
                }
                message = if (created) R.string.backup_saved else 0
            } finally { busy = false }
        }
    }

    val leave = { if (!busy) onBack() }
    BackHandler(onBack = leave)
    val lastLabel = if (last == 0L) "" else DateFormat.getMediumDateFormat(context).format(Date(last)) + " " + DateFormat.getTimeFormat(context).format(Date(last))
    val messageLabel = if (message != 0) stringResource(message) else ""

    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.backup_title), onBack = leave, subtitle = stringResource(R.string.backup_subtitle))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.backup_heading), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.backup_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { create.launch("FancyAI-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())}.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_create)) }
            OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_restore)) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.backup_automatic_section), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val toggleTitle = stringResource(R.string.backup_daily)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(toggleTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CompactSwitch(enabled = !busy, checked = enabled, onCheckedChange = { value ->
                    if (value && folder == null) chooseFolder.launch(null)
                    else scope.launch {
                        busy = true
                        try { withContext(Dispatchers.IO) { BackupSchedule.configure(app, null, value) } }
                        finally { busy = false }
                    }
                }, label = toggleTitle)
            }
            Text(stringResource(R.string.backup_automatic_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = { chooseFolder.launch(folder?.let(Uri::parse)) }) { Text(stringResource(R.string.backup_folder)) }
            folder?.let { Uri.decode(it.toUri().lastPathSegment.orEmpty()) }.orEmpty().takeIf(String::isNotEmpty)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(if (lastLabel.isEmpty()) stringResource(R.string.backup_never) else stringResource(R.string.backup_last, lastLabel), style = MaterialTheme.typography.bodySmall, color = AccentSoft)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall)
            }
            if (messageLabel.isNotEmpty()) Text(messageLabel, style = MaterialTheme.typography.bodySmall, color = AccentSoft)
        }
    }
    BackupRestoreDialog(restoreUri, busy, onRestore = { uri ->
        restoreUri = null
        scope.launch {
            busy = true
            message = 0
            try {
                val restored = ContentBackup.lock.withLock { ContentBackup.restore(app, uri) }
                message = if (restored) R.string.backup_restored else R.string.backup_no_content
            } finally { busy = false }
        }
    }) { restoreUri = null }
}

@Composable
private fun BackupRestoreDialog(uri: Uri?, busy: Boolean, onRestore: (Uri) -> Unit, onDismiss: () -> Unit) {
    if (uri == null) return
    AppDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.backup_restore)) },
        text = { Text(stringResource(R.string.backup_confirm)) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                onRestore(uri)
            }) { Text(stringResource(R.string.backup_restore)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun GeneralSettingsScreen(onReplayIntro: () -> Unit, onBack: () -> Unit) {
    var showingGuide by rememberSaveable { mutableStateOf(false) }
    if (showingGuide) {
        UserGuideScreen(onBack = { showingGuide = false })
        return
    }
    val context = LocalContext.current
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val languages = remember(context) {
        val supported = LocaleConfig(context).supportedLocales!!
        listOf(Locale.ROOT) + List(supported.size()) { supported[it] }
    }
    val selected = localeManager.applicationLocales[0] ?: Locale.ROOT
    val systemDefault = stringResource(R.string.settings_language_system)
    var languageMenuOpen by remember { mutableStateOf(false) }
    val prefs = remember(context) { context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE) }
    var ramMonitorEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_RAM_MONITOR, false)) }
    var hfMirrorEnabled by remember { mutableStateOf(prefs.getBoolean(KEY_HF_MIRROR, false)) }
    BackHandler { if (languageMenuOpen) languageMenuOpen = false else onBack() }
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.settings_general), onBack = onBack, subtitle = stringResource(R.string.settings_general_summary))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .clickable(role = Role.Button) { languageMenuOpen = true }
                        .padding(vertical = 12.dp, horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (selected == Locale.ROOT) systemDefault else selected.getDisplayName(selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(painterResource(R.drawable.ic_expand), contentDescription = null, tint = AccentSoft)
                }
                DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                    languages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(if (language == Locale.ROOT) systemDefault else language.getDisplayName(language)) },
                            onClick = {
                                languageMenuOpen = false
                                localeManager.applicationLocales = if (language == Locale.ROOT) {
                                    LocaleList.getEmptyLocaleList()
                                } else LocaleList(language)
                            },
                            trailingIcon = if (language.language == selected.language) {
                                { Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = AccentSoft) }
                            } else null,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            CompactSwitchRow(
                title = stringResource(R.string.settings_ram_monitor),
                summary = stringResource(R.string.settings_ram_monitor_summary),
                checked = ramMonitorEnabled,
                onCheckedChange = { enabled ->
                    ramMonitorEnabled = enabled
                    prefs.edit { putBoolean(KEY_RAM_MONITOR, enabled) }
                },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            CompactSwitchRow(
                title = stringResource(R.string.settings_hf_mirror),
                summary = stringResource(R.string.settings_hf_mirror_summary),
                checked = hfMirrorEnabled,
                onCheckedChange = { enabled ->
                    hfMirrorEnabled = enabled
                    prefs.edit { putBoolean(KEY_HF_MIRROR, enabled) }
                },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SettingsRow(
                title = stringResource(R.string.settings_user_guide),
                summary = stringResource(R.string.settings_user_guide_summary),
                onClick = { showingGuide = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SettingsRow(
                title = stringResource(R.string.settings_replay_intro),
                summary = stringResource(R.string.settings_replay_intro_summary),
                onClick = onReplayIntro,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
