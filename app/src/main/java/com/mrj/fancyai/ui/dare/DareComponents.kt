package com.mrj.fancyai.ui.dare

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.ThoughtProcess
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.SocialActiveGeneration
import com.mrj.fancyai.ui.social.SocialAvatar
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun DarePostCard(
    post: DarePost,
    character: CharacterCard?,
    userProfile: UserProfile,
    actionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerateImage: () -> Unit = {},
    pending: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate.copy(alpha = 0.5f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = DARE_FEED_WIDTH)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SocialAvatar(
                    character = character,
                    profile = userProfile,
                    user = false,
                    fallbackName = post.characterName,
                    size = 28.dp,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = post.authorHandle.ifBlank { post.characterName },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            post.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!pending) {
                    DarePostMenu(
                        actionsEnabled = actionsEnabled,
                        onDelete = onDelete,
                        onRegenerateImage = onRegenerateImage,
                    )
                }
            }

            if (post.dareTitle.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent.copy(alpha = 0.12f))
                        .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "DARE: ${post.dareTitle}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Accent,
                    )
                }
            }

            ThoughtProcess(post.thoughtProcess)

            if (post.caption.isNotBlank()) {
                MessageMarkdown(
                    post.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (post.image.isFile) {
                MessageImage(
                    imagePath = post.image.absolutePath,
                    description = R.string.dare_post_image,
                    topPadding = 12.dp,
                    cornerRadius = 8.dp,
                    aspectRatioRange = 0.58f..1.8f,
                )
            }

            ImagePromptSection(post.image, savedPrompt = post.imagePrompt.takeIf(String::isNotBlank))
            content()
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun DarePostMenu(
    actionsEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerateImage: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(value = false) }
    Box {
        val menuLabel = stringResource(R.string.action_more_options)
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics { contentDescription = menuLabel },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = SlateRaised,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.image_regenerate),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                enabled = actionsEnabled,
                onClick = {
                    menuOpen = false
                    onRegenerateImage()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.action_delete),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                enabled = actionsEnabled,
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
internal fun DareLoading() {
    val loadingLabel = stringResource(R.string.dare_loading)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .semantics { contentDescription = loadingLabel },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(24.dp))
    }
}

@Composable
internal fun DareEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_empty),
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.social_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.dare_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .widthIn(max = 300.dp),
        )
    }
}

@Composable
internal fun DareGenerateBar(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onGenerate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PostInput(
            value = value,
            hint = stringResource(R.string.dare_generate_summary),
            singleLine = false,
            minLines = 1,
            maxLines = 3,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            content = if (value.isNotBlank()) {
                {
                    val clearLabel = stringResource(R.string.action_clear_feed)
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .size(28.dp)
                            .semantics { contentDescription = clearLabel },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else null,
        )
        TextButton(
            enabled = enabled,
            onClick = onGenerate,
        ) {
            Text(
                text = stringResource(R.string.dare_new_dare),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    HorizontalDivider(color = Hairline)
}



@Composable
internal fun DareGenerationPanel(
    generation: DareGeneration,
    posts: List<DarePost>,
    onStop: () -> Unit,
) {
    val draft = (generation as? DareGeneration.Rendering)?.draft
    if ((draft != null) && posts.none { it.id == draft.id }) {
        val pendingPost = DarePost(
            id = "pending",
            characterId = draft.character.id,
            characterName = draft.character.name,
            authorHandle = draft.character.handle,
            dareTitle = draft.dareTitle,
            caption = draft.caption,
            createdAt = System.currentTimeMillis(),
            imagePath = "",
            image = java.io.File(""),
            thoughtProcess = draft.thoughtProcess,
            imagePrompt = draft.imagePrompt.orEmpty(),
        )
        DarePostCard(
            post = pendingPost,
            character = draft.character,
            userProfile = UserProfile("", ""),
            actionsEnabled = false,
            onDelete = {},
            pending = true,
            content = { DareGenerationStatus(generation = generation, onStop = onStop) },
        )
    } else {
        DareGenerationStatus(generation = generation, onStop = onStop)
    }
}

@Composable
private fun DareGenerationStatus(
    generation: DareGeneration,
    onStop: () -> Unit,
) {
    when (generation) {
        DareGeneration.Idle -> Unit
        is DareGeneration.Writing -> SocialActiveGeneration(
            character = generation.character,
            title = stringResource(R.string.social_writing_title),
            onStop = onStop,
        )
        is DareGeneration.Rendering -> SocialActiveGeneration(
            character = generation.character,
            title = stringResource(R.string.chat_image_generating),
            progress = generation.progress,
            onStop = onStop,
        )
    }
}
