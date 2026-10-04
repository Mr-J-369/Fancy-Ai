package com.mrj.fancyai.ui.rebbit

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.ThoughtProcess
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.SocialAvatar
import com.mrj.fancyai.ui.social.SocialComment
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun RebbitPostCard(
    post: RebbitPost, character: CharacterCard?, userProfile: UserProfile, commentCount: Int, expanded: Boolean,
    actionsEnabled: Boolean,
    onComments: () -> Unit, onCommunity: () -> Unit,
    onDelete: () -> Unit, onReport: () -> Unit,
    onRegenerateImage: () -> Unit = {},
    pending: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    var menuOpen by remember(post.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().background(if (expanded) Ink else Slate.copy(alpha = 0.5f)), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = FEED_WIDTH).fillMaxWidth().clickable(enabled = !expanded && !pending, role = Role.Button, onClick = onComments).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SocialAvatar(character, userProfile, false, post.characterName, 28.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    if (post.community.isNotBlank()) Text(post.community, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight().clickable(role = Role.Button, onClick = onCommunity))
                    Text(stringResource(R.string.two_part_meta, post.authorHandle.ifBlank { post.characterName }, DateUtils.getRelativeTimeSpanString(post.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!pending) Box {
                    val menuLabel = stringResource(R.string.action_more_options)
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
                        Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = SlateRaised) {

                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.image_regenerate), style = MaterialTheme.typography.labelMedium) },
                            enabled = actionsEnabled,
                            onClick = { menuOpen = false; onRegenerateImage() },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium) }, enabled = actionsEnabled, onClick = { menuOpen = false; onDelete() })
                        DropdownMenuItem(text = { Text(stringResource(if (post.reported) R.string.status_reported else R.string.action_report)) }, enabled = actionsEnabled && !post.reported, onClick = { menuOpen = false; onReport() })
                    }
                }
            }
            if (post.reported) Text(stringResource(R.string.status_reported), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            ThoughtProcess(post.thoughtProcess)
            if (post.caption.isNotBlank()) MessageMarkdown(post.caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (post.image.isFile) {
                MessageImage(
                    imagePath = post.image.absolutePath,
                    description = R.string.rebbit_post_image,
                    topPadding = 14.dp,
                    cornerRadius = 8.dp,
                    aspectRatioRange = 0.58f..1.8f,
                )
            }
            ImagePromptSection(post.image, savedPrompt = post.imagePrompt.takeIf(String::isNotBlank))
            content()
            if (!expanded && !pending) {
                TextButton(onClick = onComments, modifier = Modifier.padding(top = 6.dp)) {
                    Text(pluralStringResource(R.plurals.rebbit_comments_action, commentCount, commentCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun RebbitLoading() {
    val loadingLabel = stringResource(R.string.rebbit_loading)
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).semantics { contentDescription = loadingLabel }, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(24.dp)) }
}

@Composable
internal fun RebbitEmpty() {
    Column(Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.social_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.rebbit_empty_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp))
    }
}

@Composable
internal fun RebbitController.RebbitThreadPage(post: RebbitPost, actionsEnabled: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val target = threadComments.firstOrNull { it.id == replyToId }
    com.mrj.fancyai.ui.social.SocialCommentsScreen(
        title = stringResource(R.string.social_comments_title, post.characterName),
        empty = threadComments.isEmpty(), latestKey = threadComments.lastOrNull(),
        draft = commentDraft, hint = stringResource(R.string.social_comment_hint),
        emptyMessage = stringResource(R.string.social_comments_empty),
        status = replyStatus, imageProgress = replyImageProgress,
        sendEnabled = actionsEnabled && !threadLoading, containerColor = Ink,
        onDraftChange = {
            commentDraft = it
            app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).edit { putString(KEY_COMMENT_DRAFT_PREFIX + post.id, it) }
        },
        onSend = { sendComment(post) }, onDismiss = onBack, onStop = ::stopGeneration,
        replyTarget = target,
        onClearReply = {
            replyToId = ""
            app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).edit { putString(KEY_COMMENT_DRAFT_PREFIX + post.id + "_parent", "") }
        },
        comments = {
            items(threadComments.asReversed(), key = SocialComment::id) { comment ->
                com.mrj.fancyai.ui.social.SocialCommentRow(comment, characters.firstOrNull { it.id == comment.authorId }, profile,
                    selected = comment.id == replyToId, onReply = {
                        replyToId = comment.id
                        app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).edit { putString(KEY_COMMENT_DRAFT_PREFIX + post.id + "_parent", comment.id) }
                    })
            }
        },
    ) {
        if (threadLoading) RebbitLoading()
        MessageMarkdown(post.caption, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(bottom = 12.dp))
    }
}
