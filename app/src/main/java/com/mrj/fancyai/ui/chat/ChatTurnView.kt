package com.mrj.fancyai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.ThoughtProcess
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.util.contentImageFile

@Composable
internal fun ChatController.UserMessage(
    userName: String,
    turn: ChatTurn,
    index: Int,
    canRegenerate: Boolean,
    editor: ChatMessageEditor,
    onDelete: (Int) -> Unit,
) {
    var userActionsExpanded by remember { mutableStateOf(value = false) }
    val copyDescription = stringResource(R.string.chat_message_copy)
    val moreDescription = stringResource(R.string.chat_message_more)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = userName,
            style = MaterialTheme.typography.labelMedium,
            color = AccentSoft,
        )
        ChatIconButton(
            icon = R.drawable.ic_copy,
            description = copyDescription,
            tint = AccentSoft,
            onClick = { copyMessage(turn.user) },
        )
        ChatIconButton(
            icon = R.drawable.ic_more,
            description = moreDescription,
            onClick = { userActionsExpanded = !userActionsExpanded },
        )
    }
    MessageMarkdown(
        content = turn.user,
        style = MaterialTheme.typography.bodyMedium,
        color = AccentSoft,
        modifier = Modifier.fillMaxWidth(),
    )
    MessageImage(
        turn.userImagePath,
        R.string.chat_user_image_description,
    )
    AnimatedVisibility(visible = userActionsExpanded) {
        MessageActions(
            enabled = !busy && !listening,
            showRegenerate = canRegenerate && turn.replyText.isEmpty(),
            canRegenerate = canRegenerate,
            onEdit = { editor.open(this@UserMessage, index, true, turn.user) },
            onRegenerate = { regenerateReply(index) },
            onDelete = { onDelete(index) },
            onDismiss = { userActionsExpanded = false },
        )
    }
}

@Composable
internal fun ChatController.AssistantMessage(
    turn: ChatTurn,
    index: Int,
    canRegenerate: Boolean,
    editor: ChatMessageEditor,
    onDelete: (Int) -> Unit,
) {
    val (replyText, imagePrompt) = remember(turn.assistant) {
        turn.replyText to turn.imagePromptText
    }
    val hasReply = replyText.isNotEmpty() || turn.imagePath != null || imagePrompt != null || turn.sudoCommand != null
    var actionsExpanded by remember { mutableStateOf(value = false) }
    val reply = SpeakingReply(activeChatId, index)
    val speaking = speakingReply == reply
    if (hasReply || turn.channels.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape)) {
                Artwork(
                    path = character.avatarPath,
                    resource = character.avatarResource,
                    contentDescription = character.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = character.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            if (replyText.isNotEmpty()) {
                ChatIconButton(
                    icon = if (speaking) R.drawable.ic_stop else R.drawable.ic_play,
                    description = stringResource(
                        if (speaking) R.string.chat_message_stop_speaking else R.string.chat_message_speak,
                    ),
                    enabled = speaking || (!generating && !rebuilding),
                    tint = if (speaking) Accent else AccentSoft,
                    onClick = { toggleSpeaking(index, turn.replyText) },
                    disabledAlpha = 0.38f,
                )
                ChatIconButton(
                    icon = R.drawable.ic_copy,
                    description = stringResource(R.string.chat_message_copy),
                    tint = AccentSoft,
                    onClick = { copyMessage(turn.replyText) },
                )
            }
            ChatIconButton(
                icon = R.drawable.ic_more,
                description = stringResource(R.string.chat_message_more),
                onClick = { actionsExpanded = !actionsExpanded },
            )
        }
    }
    AnimatedVisibility(visible = actionsExpanded && hasReply) {
        MessageActions(
            enabled = !busy && !listening,
            canRegenerate = canRegenerate,
            onEdit = { editor.open(this@AssistantMessage, index, false, turn.replyText) },
            onRegenerate = { regenerateReply(index) },
            onDelete = { onDelete(index) },
            onGenerateImage = { generateReplyImage(index) },
            onDismiss = { actionsExpanded = false },
        )
    }
    AssistantReplyContent(turn, ttsError == reply, index)
}

@Composable
private fun ChatController.AssistantReplyContent(turn: ChatTurn, speechFailed: Boolean, index: Int) {
    ThoughtProcess(
        content = turn.channels.values.joinToString(separator = "\n\n"),
        modifier = Modifier.padding(top = 10.dp),
    )
    if (turn.replyText.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Box(Modifier.size(width = 2.dp, height = 24.dp).background(Accent))
            MessageMarkdown(
                content = turn.replyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        if (speechFailed) {
            Text(
                text = stringResource(R.string.chat_speech_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp),
            )
        }
    }
    if (turn.sudoCommand != null) {
        ChatTerminalCard(
            turn = turn,
            index = index,
            controller = this,
        )
    }
    MessageImage(
        turn.imagePath,
        R.string.chat_generated_image_description,
        topPadding = 14.dp,
    )
    val pendingPrompt = turn.imagePromptText
    if (turn.imagePath != null) {
        val image = contentImageFile(LocalContext.current, turn.imagePath)
        ImagePromptSection(image, savedPrompt = pendingPrompt)
    } else {
        pendingPrompt?.let { ImagePromptSection(null, savedPrompt = it) }
    }
}

@Composable
private fun MessageActions(
    enabled: Boolean,
    canRegenerate: Boolean,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    showRegenerate: Boolean = true,
    onGenerateImage: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        MessageAction(
            mark = R.drawable.ic_edit,
            label = stringResource(R.string.action_edit),
            enabled = enabled,
            onClick = {
                onDismiss()
                onEdit()
            },
            modifier = Modifier.weight(1f),
        )
        if (showRegenerate) {
            MessageAction(
                mark = R.drawable.ic_refresh,
                label = stringResource(R.string.chat_message_regenerate),
                enabled = enabled && canRegenerate,
                onClick = {
                    onDismiss()
                    onRegenerate()
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (onGenerateImage != null) {
            MessageAction(
                mark = R.drawable.ic_image,
                label = stringResource(R.string.vision_image),
                enabled = enabled,
                onClick = {
                    onDismiss()
                    onGenerateImage()
                },
                modifier = Modifier.weight(1f),
            )
        }
        MessageAction(
            mark = R.drawable.ic_delete,
            label = stringResource(R.string.action_delete),
            enabled = enabled,
            destructive = true,
            onClick = {
                onDismiss()
                onDelete()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MessageAction(
    mark: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(mark), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
internal fun EmptyConversation(
    loading: Boolean,
    canRetry: Boolean,
    greeting: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = if (loading) stringResource(R.string.chat_waking_message) else greeting
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.chat_empty_eyebrow),
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
        )
        if (message.isNotBlank()) {
            MessageMarkdown(
                content = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (canRetry) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_retry_engine),
                    color = Accent,
                )
            }
        }
    }
}
