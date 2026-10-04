package com.mrj.fancyai.ui.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mrj.fancyai.MainActivity
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.VoiceEngineFactory
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PhoneScreen(onBack: () -> Unit) {
    var character by remember { mutableStateOf<CharacterCard?>(null) }
    var historySelected by remember { mutableStateOf(false) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var historyFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var historyCall by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = character
    if (selected == null) {
        PhoneDirectory(
            historySelected, { historySelected = it }, historyRevision,
            historyFolder, historyCall,
            { historyFolder = it; historyCall = null }, { historyCall = it }, onBack,
        ) { character = it }
    } else {
        PhoneCall(character = selected, onHistoryChanged = { historyRevision++ }) { character = null }
    }
}

@Composable
private fun PhoneDirectory(
    historySelected: Boolean,
    onSelectHistory: (Boolean) -> Unit,
    historyRevision: Int,
    historyFolder: String?,
    historyCall: String?,
    onHistoryFolder: (String?) -> Unit,
    onHistoryCall: (String?) -> Unit,
    onBack: () -> Unit,
    onCall: (CharacterCard) -> Unit,
) {
    val context = LocalContext.current
    var search by remember(context) { mutableStateOf(context.getSharedPreferences(PHONE_PREFERENCES, Context.MODE_PRIVATE).getString(KEY_PHONE_SEARCH, "").orEmpty()) }
    var characters by remember(context) { mutableStateOf<List<CharacterCard>?>(null) }
    LaunchedEffect(context) {
        characters = withContext(Dispatchers.IO) { availableCharacters(context) }
    }
    val filteredCharacters = characters.orEmpty().filter {
        it.name.contains(search, ignoreCase = true) || it.handle.contains(search, ignoreCase = true)
    }

    val goBack = {
        when {
            historySelected && historyCall != null -> onHistoryCall(null)
            historySelected && historyFolder != null -> onHistoryFolder(null)
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        AppHeader(title = stringResource(R.string.home_app_phone), onBack = goBack, subtitle = stringResource(if (historySelected) R.string.phone_history else R.string.phone_subtitle))
        PhoneTabs(historySelected, onSelectHistory)
        if (historySelected) {
            PhoneHistory(
                historyRevision, historyFolder, historyCall, onHistoryFolder, onHistoryCall,
                characters.orEmpty().associateBy(CharacterCard::id),
            )
        } else {
            PostInput(
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                value = search,
                hint = stringResource(R.string.characters_search_hint),
                onValueChange = {
                    search = it
                    context.getSharedPreferences(PHONE_PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY_PHONE_SEARCH, it) }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.phone_choose_character),
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
            if (characters == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filteredCharacters, key = CharacterCard::id) { character ->
                    PhoneCharacterRow(character = character) { onCall(character) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                }
                if (characters != null && filteredCharacters.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.characters_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneCharacterRow(character: CharacterCard, onCall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onCall)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            path = character.avatarPath,
            resource = character.avatarResource,
            contentDescription = character.name,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = character.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (character.handle.isNotBlank()) {
                Text(
                    text = character.handle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(painter = painterResource(R.drawable.ic_record), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PhoneCall(character: CharacterCard, onHistoryChanged: () -> Unit, onEnd: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val profile = remember(character.id) { userProfile(context) }
    val userName = profile.name.ifBlank { stringResource(R.string.chat_user_name) }
    val firstMessage = remember(character, profile, userName) { MacroBus(context, character, profile, userName).text(character.firstMessage) }
    val scope = rememberCoroutineScope()
    val controller = remember(character.id) { PhoneController(context, character, profile, userName, firstMessage, scope, onHistoryChanged) }
    val partialSpeech by controller.stt.partial.collectAsState()
    val phase by controller.phase
    val muted by controller.muted
    val lastHeard by controller.lastHeard
    val thoughtProcess by controller.thoughtProcess
    val error by controller.error

    fun leaveCall() { controller.closeSession(); onEnd() }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.beginCall() else { controller.error.intValue = R.string.phone_permission_denied; controller.phase.value = PhonePhase.Error }
    }
    fun requestCall() {
        when {
            !VoiceEngineFactory.sttAvailable(context) -> { controller.error.intValue = R.string.chat_voice_unavailable; controller.phase.value = PhonePhase.Error }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> controller.beginCall()
            else -> requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) { onDispose { controller.dispose() } }
    BackHandler(onBack = ::leaveCall)
    val callActive = phase != PhonePhase.Ready && phase != PhonePhase.Error
    DisposableEffect(activity, callActive, character.name) {
        activity?.configurePhonePictureInPicture(active = callActive, title = character.name, onHidden = if (callActive) ::leaveCall else null)
        onDispose { activity?.configurePhonePictureInPicture(active = false, title = character.name, onHidden = null) }
    }
    if (activity?.inPhonePictureInPicture == true) PhoneCallPipSurface(character, phase) else PhoneCallSurface(
        character = character, phase = phase, muted = muted,
        heard = if (phase == PhonePhase.Listening) partialSpeech else lastHeard,
        thoughtProcess = thoughtProcess,
        error = error, onBack = ::leaveCall, onStart = ::requestCall,
        onMute = controller::toggleMute, onStop = controller::stopSpeaking, onEnd = ::leaveCall,
    )
}



@Composable
internal fun PhoneTabs(historySelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(top = 16.dp)) {
        listOf(R.string.phone_contacts, R.string.phone_history).forEachIndexed { index, label ->
            val selected = historySelected == (index == 1)
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.Tab) { onSelect(index == 1) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = if (selected) Accent else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
internal fun PhoneHistory(
    revision: Int,
    folderId: String?,
    callId: String?,
    onFolder: (String?) -> Unit,
    onOpenCall: (String?) -> Unit,
    characters: Map<String, CharacterCard>,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { PhoneHistoryController(context, scope) }
    var pendingRemoval by remember { mutableStateOf<PhoneHistoryEntry?>(null) }
    LaunchedEffect(context, revision) {
        controller.load()
    }
    val calls = controller.entries.value
    if (calls == null) {
        if (!controller.failed.value) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
        return
    }
    val folderCalls = calls.filter { it.characterId == folderId }
    val selected = folderCalls.firstOrNull { it.id == callId }
    val name = characters[folderId]?.name ?: folderCalls.firstOrNull()?.name.orEmpty()
    if (folderId != null) {
        Text(name, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp))
    }

    if (selected != null) {
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(selected.startedAt)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        PhoneCallConversation(selected.id, revision)
    } else if (calls.isEmpty() || (folderId != null && folderCalls.isEmpty())) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
            Text(stringResource(R.string.phone_history_empty), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.phone_history_empty_detail), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    } else if (folderId == null) {
        PhoneHistoryFolderList(calls, characters, locale, onFolder)
    } else {
        PhoneHistoryCallList(folderCalls, locale, onOpenCall) { pendingRemoval = it }
    }
    pendingRemoval?.let { entry ->
        AppDialog(
            onDismissRequest = { if (!controller.removing.value) pendingRemoval = null },
            title = { Text(stringResource(R.string.phone_history_remove)) },
            text = { Text(stringResource(R.string.phone_history_remove_detail, entry.name)) },
            confirmButton = {
                TextButton(
                    enabled = !controller.removing.value,
                    onClick = { controller.remove(entry) { pendingRemoval = null } },
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(enabled = !controller.removing.value, onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
internal fun callSummary(entry: PhoneHistoryEntry): String {
    val status = stringResource(when (entry.status) {
        PhoneCallStatus.ENDED -> R.string.phone_history_ended
        PhoneCallStatus.CANCELLED -> R.string.phone_history_cancelled
        PhoneCallStatus.FAILED -> R.string.phone_history_call_failed
        PhoneCallStatus.INTERRUPTED -> R.string.phone_history_interrupted
    })
    return if (entry.status == PhoneCallStatus.INTERRUPTED) status else stringResource(
        R.string.two_part_meta, status, DateUtils.formatElapsedTime(entry.durationSeconds),
    )
}

@Composable
internal fun PhoneCallConversation(callId: String, revision: Int) {
    val context = LocalContext.current
    var turns by remember(callId) { mutableStateOf<List<PhoneTurn>?>(null) }
    var failed by remember(callId) { mutableStateOf(false) }
    LaunchedEffect(callId, revision) {
        runCatching { withContext(Dispatchers.IO) { readPhoneTurns(context, callId) } }
            .onSuccess { turns = it; failed = false }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Phone", "Transcript load failed", failure)
                failed = true
            }
    }
    val loadedTurns = turns
    when {
        loadedTurns == null -> if (!failed) LinearProgressIndicator(Modifier.fillMaxWidth())
        loadedTurns.isEmpty() -> Text(stringResource(R.string.phone_history_no_exchange),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
        else -> SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(loadedTurns) { turn ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(turn.speaker, style = MaterialTheme.typography.labelSmall, color = Accent,
                            modifier = Modifier.padding(bottom = 6.dp))
                        com.mrj.fancyai.ui.kit.ThoughtProcess(turn.thoughtProcess)
                        MessageMarkdown(turn.text, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
                        turn.imagePath?.let { path ->
                            Artwork(java.io.File(context.filesDir, path).path, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp), contentDescription = stringResource(R.string.aura_result_description))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneHistoryFolderList(
    calls: List<PhoneHistoryEntry>,
    characters: Map<String, CharacterCard>,
    locale: java.util.Locale,
    onFolder: (String) -> Unit,
) {
    val folders = calls.groupBy { it.characterId }.entries.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(folders, key = { it.key }) { (id, history) ->
            val character = characters[id]
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp)
                .clickable(role = Role.Button) { onFolder(id) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (character != null) {
                    Artwork(
                        path = character.avatarPath,
                        resource = character.avatarResource,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(character?.name ?: history.first().name, style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.phone_history_calls, history.size, history.size),
                        style = MaterialTheme.typography.labelSmall, color = Accent, modifier = Modifier.padding(top = 4.dp))
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(history.first().startedAt)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PhoneHistoryCallList(
    entries: List<PhoneHistoryEntry>,
    locale: java.util.Locale,
    onOpenCall: (String) -> Unit,
    onRemove: (PhoneHistoryEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(entries, key = { it.id }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 88.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button) { onOpenCall(entry.id) }
                    .padding(vertical = 16.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(entry.startedAt)), style = MaterialTheme.typography.titleSmall)
                    Text(callSummary(entry), style = MaterialTheme.typography.labelSmall, color = Accent,
                        modifier = Modifier.padding(top = 6.dp))
                }
                TextButton(onClick = { onRemove(entry) }) { Text(stringResource(R.string.action_remove)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
