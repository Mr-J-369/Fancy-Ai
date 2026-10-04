package com.mrj.fancyai.ui.characters

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun RootCreatorScreen(
    onBack: () -> Unit,
    onSaved: (CharacterCard) -> Unit,
) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(value = false) }
    val requestExit = rememberSessionExit(onBack)
    val leave = { if (editing) editing = false else requestExit() }

    BackHandler(onBack = leave)
    Box(Modifier.fillMaxSize().background(Ink)) {
        Image(
            painter = painterResource(R.drawable.root_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.2f,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        0.42f to Ink.copy(alpha = 0.9f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            AppHeader(
                title = stringResource(R.string.home_app_root_creator),
                subtitle = if (editing) null else stringResource(R.string.root_creator_subtitle),
                onBack = leave,
            )
            if (editing) {
                CharacterEditorScreen(
                    character = null,
                    editorKey = ROOT_CREATOR_DRAFT_KEY,
                    onSaved = { character ->
                        context.getSharedPreferences("root_creator", Context.MODE_PRIVATE).edit { remove("idea") }
                        onSaved(character)
                    },
                )
            } else {
                val scope = rememberCoroutineScope()
                val controller = remember(context) { RootCreatorController(context.applicationContext) }
                DisposableEffect(controller) { onDispose { controller.close() } }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.root_creator_desk),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentSoft,
                        )
                        Text(
                            text = stringResource(R.string.root_creator_prompt),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = stringResource(R.string.root_creator_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.92f),
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.root_creator_idea_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                        )
                        PostInput(
                            value = controller.idea,
                            hint = stringResource(R.string.root_creator_idea_hint),
                            enabled = !controller.generating,
                            modifier = Modifier.padding(top = 6.dp).heightIn(min = 112.dp),
                            onValueChange = controller::updateIdea,
                        )
                    }
                    item { com.mrj.fancyai.ui.kit.ThoughtProcess(controller.thoughtProcess) }
                    controller.imagePath?.let { path ->
                        item { com.mrj.fancyai.ui.kit.Artwork(path, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp), contentDescription = stringResource(R.string.aura_result_description)) }
                    }
                    rootCreatorActions(controller, scope, onEdit = { editing = true })
                }
            }
        }
    }
}

private fun LazyListScope.rootCreatorActions(
    controller: RootCreatorController,
    scope: CoroutineScope,
    onEdit: () -> Unit,
) {
    with(controller) {
        item {
            val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .clickable(role = Role.Button) {
                        scope.launch { if (writeDraft()) onEdit() }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.requiredWidth(3.dp).heightIn(min = 48.dp).background(Accent))
                Text(
                    text = stringResource(
                        when {
                            generating -> R.string.root_creator_stop
                            hasDraft -> R.string.root_creator_rewrite
                            else -> R.string.root_creator_write
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                Icon(painter = painterResource(if (generating) R.drawable.ic_stop else R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.padding(end = 16.dp).size(22.dp))
            }
            Text(
                text = stringResource(R.string.root_creator_engine_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (error != 0) {
                Text(
                    text = stringResource(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (hasDraft && !generating) {
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onEdit)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.root_creator_continue),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.root_creator_continue_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
