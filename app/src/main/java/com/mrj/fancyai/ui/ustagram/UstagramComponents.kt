package com.mrj.fancyai.ui.ustagram

import android.text.format.DateUtils
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.ThoughtProcess
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.SocialAvatar
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun UstagramPostCard(
    post: UstagramPost,
    character: CharacterCard?,
    profile: UserProfile,
    actionsEnabled: Boolean,
    onComments: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onRegenerateImage: () -> Unit = {},
    pending: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 10.dp)) {
            UstagramPostHeader(
                post, character, profile, pending, actionsEnabled,
                onRegenerateImage, onDelete, onReport,
            )
            if (post.photo.isFile) {
                MessageImage(
                    imagePath = post.photo.absolutePath,
                    description = R.string.ustagram_photo_description,
                    topPadding = 0.dp,
                    cornerRadius = 0.dp,
                    contentScale = ContentScale.Crop,
                    aspectRatioRange = 1f..1f,
                )
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThoughtProcess(post.thoughtProcess)
                ImagePromptSection(post.photo, savedPrompt = post.imagePrompt.takeIf(String::isNotBlank))
                if (post.caption.isNotBlank()) {
                    MessageMarkdown(
                        content = post.caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }

                if (post.theme.isNotBlank()) {
                    Text(
                        post.theme,
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (post.reported) {
                    Text(
                        stringResource(R.string.status_reported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Text(
                    DateUtils.getRelativeTimeSpanString(post.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }

            content()
            if (!pending) Text(
                stringResource(R.string.social_comments_title),
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = actionsEnabled, role = Role.Button, onClick = onComments)
                    .padding(horizontal = 12.dp, vertical = 17.dp),
            )
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun UstagramPostHeader(
    post: UstagramPost,
    character: CharacterCard?,
    profile: UserProfile,
    pending: Boolean,
    actionsEnabled: Boolean,
    onRegenerateImage: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SocialAvatar(
            character = character,
            profile = profile,
            user = false,
            fallbackName = post.characterName,
            size = 32.dp,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                post.characterName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (post.authorHandle.isNotBlank()) {
                Text(
                    post.authorHandle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!pending) {
            var menuOpen by remember(post.id) { mutableStateOf(value = false) }
            Box {
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more_options), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp).clickable(role = Role.Button) { menuOpen = true }.padding(13.dp))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = SlateRaised) {
                    listOf(
                        Triple(R.string.image_regenerate, actionsEnabled, onRegenerateImage),
                        Triple(R.string.action_delete, actionsEnabled, onDelete),
                        Triple(if (post.reported) R.string.status_reported else R.string.action_report, actionsEnabled && !post.reported, onReport),
                    ).forEach { (label, enabled, action) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
                            enabled = enabled,
                            onClick = {
                                menuOpen = false
                                action()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UstagramLoading() {
    val description = stringResource(R.string.ustagram_loading)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 240.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(24.dp))
    }
}

@Composable
internal fun UstagramEmpty() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.ustagram_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.ustagram_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
        )
    }
}
