package com.mrj.fancyai.ui.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import java.io.File

@Composable
internal fun PeopleTile(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 6.dp)),
        ) {
            content()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Ink)),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun ImageChoice(
    title: String,
    summary: String,
    image: File?,
    revision: Int,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    enabled: Boolean = true,
    onGenerate: (() -> Unit)? = null,
    generating: Boolean = false,
    onRemove: () -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (image != null) Artwork(image.absolutePath, Modifier.fillMaxSize(), revision = revision)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            if (onGenerate != null) {
                Text(
                    stringResource(if (generating) R.string.action_stop else R.string.action_generate),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled || generating) Accent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .clickable(enabled = enabled || generating, role = Role.Button, onClick = onGenerate)
                        .padding(top = 16.dp),
                )
            }
            Text(
                stringResource(R.string.character_choose),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Accent else MaterialTheme.colorScheme.outline,
                modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onPick).padding(top = 16.dp),
            )
            if (image != null) {
                Text(
                    stringResource(R.string.action_remove),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onRemove).padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
internal fun CharacterProfile(
    character: CharacterCard,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onExportJson: (() -> Unit)?,
    onExportPng: (() -> Unit)?,
) {
    var confirmingDelete by remember(character.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                Artwork(
                    path = character.avatarPath,
                    resource = character.avatarResource,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.58f to Ink.copy(alpha = 0.08f),
                                1f to Ink.copy(alpha = 0.94f),
                            ),
                        ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        listOf(
            Triple(R.string.field_personality, character.personality, 20),
            Triple(R.string.field_description, character.description, 18),
            Triple(R.string.character_scene, character.scene, 18),
            Triple(R.string.character_tab_look, character.appearance, 18),
        ).filter { (_, text, _) -> text.isNotBlank() }.forEach { (titleResource, text, topPadding) ->
            item {
                val title = stringResource(titleResource).let {
                    if (titleResource == R.string.field_personality) it.uppercase() else it
                }
                CharacterDetail(title, text, Modifier.padding(top = topPadding.dp))
            }
        }
        item {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 20.dp),
            )
            listOfNotNull(
                Triple(stringResource(R.string.character_edit_title), MaterialTheme.colorScheme.onSurface, onEdit),
                onExportJson?.let { Triple(stringResource(R.string.character_export_json), MaterialTheme.colorScheme.onSurface, it) },
                onExportPng?.let { Triple(stringResource(R.string.character_export_png), MaterialTheme.colorScheme.onSurface, it) },
                onDelete?.let { Triple(stringResource(R.string.character_delete), MaterialTheme.colorScheme.error) { confirmingDelete = true } },
            ).forEach { (label, color, action) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = action)
                        .padding(vertical = 17.dp),
                )
            }
        }
    }
    if (confirmingDelete && onDelete != null) {
        AppDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.character_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.character_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CharacterDetail(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
        )
        MessageMarkdown(
            content = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
