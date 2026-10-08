package com.mrj.fancyai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.flow.first

@Composable
internal fun ChatScreen(initialCharacter: CharacterCard? = null, onBack: () -> Unit) {
    var openCharacter by remember(initialCharacter?.id) { mutableStateOf(initialCharacter) }
    val character = openCharacter
    if (character != null) {
        ConversationScreen(character = character) {
            if (initialCharacter != null) onBack() else openCharacter = null
        }
    } else {
        ChatDirectory(onBack = onBack) { openCharacter = it }
    }
}

@Composable
private fun ConversationScreen(character: CharacterCard, onBack: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(character.id) { userProfile(context) }
    val userName = profile.name.ifBlank { stringResource(R.string.chat_user_name) }
    val scope = rememberCoroutineScope()
    val controller = remember(character.id, scope) {
        ChatController(context.applicationContext, character, profile, userName, scope)
    }
    var attachmentTarget by rememberSaveable(character.id) { mutableStateOf<String?>(null) }
    var templatesOpen by rememberSaveable(character.id) { mutableStateOf(false) }
    val editor = remember { ChatMessageEditor() }
    var deletingFromIndex by remember { mutableStateOf<Int?>(null) }
    with(controller) {
        val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val target = attachmentTarget
            attachmentTarget = null
            if (uri != null && target != null) importAttachment(uri, target)
        }
        val toggleListening = rememberVoiceInput()

        DisposableEffect(controller) {
            onDispose { close() }
        }
        val requestExit = rememberSessionExit {
            stopListening()
            stopSpeaking()
            stop()
            onBack()
        }
        BackHandler(onBack = requestExit)

        Box(Modifier.fillMaxSize().background(Ink)) {
            Artwork(
                path = character.backgroundPath,
                resource = character.backgroundResource,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Ink.copy(alpha = 0.52f),
                            0.42f to Ink.copy(alpha = 0.62f),
                            0.78f to Ink.copy(alpha = 0.68f),
                            1f to Ink.copy(alpha = 0.82f),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                ConversationHeader(requestExit, onOpenTemplates = { showConversations = false; templatesOpen = true })
                ConversationTranscript(userName, editor, onDelete = { deletingFromIndex = it }, modifier = Modifier.weight(1f))
                ChatComposer(
                    onVoice = toggleListening,
                    onAttach = {
                        attachmentTarget = activeChatId
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            if (templatesOpen) ChatInstructionsScreen { templatesOpen = false }
        }

        MessageDialogs(editor, deletingFromIndex, onDismissDelete = { deletingFromIndex = null })
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatController.ConversationHeader(requestExit: () -> Unit, onOpenTemplates: () -> Unit) {
    val headerLoading = loading || rebuilding || phase == ChatController.Phase.LOADING
    val conversationsDescription = stringResource(R.string.chat_conversations_open)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = character.name,
            subtitle = if (headerLoading) stringResource(R.string.chat_waking_character, character.name) else modelName,
            onBack = requestExit,
            modifier = Modifier.weight(1f),
            subtitleMaxLines = 1,
        )
        if (headerLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 1.5.dp)
        Box(
            Modifier.size(48.dp).semantics { contentDescription = conversationsDescription }
                .clickable(role = Role.Button) { showConversations = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(22.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
    if (showConversations) {
        ConversationsSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            conversations = conversations.sortedByDescending(ChatConversation::updatedAt),
            activeChatId = activeChatId,
            busy = busy,
            onDismiss = { showConversations = false },
            onSelect = ::selectConversation,
            onNew = ::newConversation,
            thinking = thinking,
            onThinkingChange = { thinking = it },
            onOpenTemplates = { showConversations = false; onOpenTemplates() },
            onRename = ::renameConversation,
            onDelete = ::deleteConversation,
        )
    }

}

internal class ChatMessageEditor {
    var index by mutableStateOf<Int?>(null)
    var user by mutableStateOf(false)
    var text by mutableStateOf("")

    fun open(controller: ChatController, index: Int, user: Boolean, original: String) {
        controller.stopSpeaking()
        this.user = user
        text = readMessageEditDraft(controller.context, controller.character.id, controller.activeChatId, index, user).ifBlank { original }
        this.index = index
    }

    fun update(controller: ChatController, index: Int, value: String) {
        text = value
        saveMessageEditDraft(controller.context, controller.character.id, controller.activeChatId, index, user, value)
    }
}

@Composable
private fun ChatController.rememberVoiceInput(): () -> Unit {
    val context = LocalContext.current
    val partialSpeech by stt.partial.collectAsState()
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else speechError = R.string.chat_voice_permission_denied
    }
    LaunchedEffect(partialSpeech, listening, recognitionSession) {
        if (listening && partialSpeech.isNotBlank()) {
            updateInput(appendSpeechToDraft(speechDraft, partialSpeech))
        }
    }

    return {
        when {
            listening -> stopListening()
            !recognitionAvailable -> speechError = R.string.chat_voice_unavailable
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()
            else -> requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun ChatController.ConversationTranscript(
    userName: String,
    editor: ChatMessageEditor,
    onDelete: (Int) -> Unit,
    modifier: Modifier,
) {
    val turns = activeConversation?.turns.orEmpty()
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var followLatest by remember(listState, activeChatId) { mutableStateOf(value = true) }
    LaunchedEffect(listState, activeChatId) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atLatest) ->
            if (scrolling || atLatest) followLatest = atLatest
        }
    }
    LaunchedEffect(listState, activeChatId, turns, generating, imeBottom) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (followLatest && !listState.isScrollInProgress && lastIndex >= 0) {
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (turns.isEmpty() || firstMessage.isNotBlank() || (!modelAvailable && !loading)) {
            item(key = "opening_message") {
                EmptyConversation(
                    loading = loading && turns.isEmpty(),
                    canRetry = !modelAvailable && !loading,
                    greeting = firstMessage,
                    onRetry = ::retryEngine,
                    modifier = if (turns.isEmpty()) Modifier.heightIn(min = 220.dp) else Modifier,
                )
            }
        }
        itemsIndexed(turns, key = { index, _ -> "$activeChatId:$index" }) { index, turn ->
            Column(Modifier.fillMaxWidth()) {
                UserMessage(
                    userName = userName,
                    turn = turn,
                    canRegenerate = index == turns.lastIndex,
                    index = index,
                    editor = editor,
                    onDelete = onDelete,
                )
                AssistantMessage(
                    turn = turn,
                    index = index,
                    canRegenerate = index == turns.lastIndex,
                    editor = editor,
                    onDelete = onDelete,
                )
            }
        }
        item(key = "conversation_end") { Spacer(Modifier.size(1.dp)) }
    }
}

@Composable
private fun ChatController.MessageDialogs(editor: ChatMessageEditor, deletingFromIndex: Int?, onDismissDelete: () -> Unit) {
    editor.index?.let { index ->
        AppDialog(
            onDismissRequest = { editor.index = null },
            title = {
                Text(
                    stringResource(
                        if (editor.user) R.string.chat_user_message_edit_title
                        else R.string.chat_message_edit_title,
                    ),
                )
            },
            text = {
                PostInput(
                    value = editor.text,
                    onValueChange = {
                        editor.update(this@MessageDialogs, index, it)
                    },
                    label = stringResource(
                        if (editor.user) R.string.chat_user_message else R.string.chat_message_reply,
                    ),
                    minLines = 2,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editor.text.isNotBlank() && !busy &&
                        (!editor.user || modelAvailable),
                    onClick = {
                        if (editor.user) editUserMessage(index, editor.text)
                        else editReply(index, editor.text)
                        editor.index = null
                    },
                ) {
                    Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium)
                }
            },
            dismissButton = {
                TextButton(onClick = { editor.index = null }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }

    deletingFromIndex?.let { index ->
        AppDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.chat_message_delete_title)) },
            text = { Text(stringResource(R.string.chat_message_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteFrom(index)
                        onDismissDelete()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
}

@Composable
internal fun ChatController.ChatComposer(onVoice: () -> Unit, onAttach: () -> Unit) {
    val enabled = modelAvailable && !busy && !importingImage
    val inputEnabled = enabled && !listening
    val voiceError = listOf(
        attachmentError, speechError, operationError,
        if (recognitionAvailable) 0 else R.string.chat_voice_unavailable,
    ).firstOrNull { it != 0 }?.let { stringResource(it) }
    val progress = imageProgress.takeIf { phase == ChatController.Phase.GENERATING_IMAGE }
    val status = when {
        phase == ChatController.Phase.GENERATING_IMAGE -> stringResource(R.string.chat_image_generating)
        importingImage -> stringResource(R.string.vision_preparing_image)
        else -> null
    }
    val voiceStatus = if (listening) stringResource(R.string.voice_listening) else status ?: voiceError
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.98f))
            .windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        attachedImagePath?.let { path ->
            MessageImage(
                path,
                R.string.chat_user_image_description,
                onRemove = ::removeAttachment,
            )
            Spacer(Modifier.height(8.dp))
        }
        PostInput(
            value = input,
            hint = stringResource(R.string.chat_message_hint_character, character.name),
            onValueChange = ::updateInput,
            enabled = inputEnabled,
            minLines = 1,
            maxLines = 5,
        )
        if (progress != null) ImageGenerationProgress(progress, Modifier.padding(bottom = 6.dp))
        else voiceStatus?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (listening || status != null) Accent else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ComposerActions(enabled, inputEnabled, onAttach, onVoice)
    }
}

@Composable
private fun ChatController.ComposerActions(
    enabled: Boolean,
    inputEnabled: Boolean,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
) {
    val sendEnabled = generating || (inputEnabled && (input.isNotBlank() || attachedImagePath != null))
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        ChatIconButton(
            icon = R.drawable.ic_add,
            description = stringResource(R.string.chat_attach_description),
            enabled = inputEnabled && !generating,
            onClick = onAttach,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        ChatIconButton(
            icon = if (listening) R.drawable.ic_stop else R.drawable.ic_mic,
            description = stringResource(if (listening) R.string.chat_voice_stop else R.string.chat_voice_input),
            enabled = listening || (enabled && recognitionAvailable && !generating),
            onClick = onVoice,
            tint = if (listening) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.border(1.dp, if (listening) Accent else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        ChatIconButton(
            icon = if (generating) R.drawable.ic_stop else R.drawable.ic_up,
            description = stringResource(if (generating) R.string.chat_stop else R.string.action_send_message),
            enabled = sendEnabled,
            onClick = if (generating) ::stop else ::send,
            tint = Ink,
            modifier = Modifier.background(
                if (sendEnabled) Accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(50),
            ),
        )
    }
}

@Composable
internal fun ChatIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledAlpha: Float = tint.alpha,
) {
    Box(
        Modifier.size(48.dp).then(modifier)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = disabledAlpha),
            modifier = Modifier.size(22.dp),
        )
    }
}
