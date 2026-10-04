package com.mrj.fancyai.ui.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.ImageChoice
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.EditorField
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.launch

@Composable
internal fun UserProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { UserProfileController(context) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val draft = controller.draft
    val mediaRevision = controller.mediaRevision
    val avatar = remember(mediaRevision) { controller.avatar }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                controller.importAvatar(uri)
                saved = false
            }
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
        AppHeader(title = stringResource(R.string.profile_title), onBack = onBack, subtitle = stringResource(R.string.profile_subtitle))
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
            contentPadding = PaddingValues(top = 34.dp, bottom = 32.dp),
        ) {
                item {
                    Text(text = stringResource(R.string.profile_about).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    listOf(
                        Triple(stringResource(R.string.field_name), draft.name, stringResource(R.string.profile_name_hint)),
                        Triple(stringResource(R.string.field_handle), draft.handle, stringResource(R.string.profile_handle_hint)),
                        Triple(stringResource(R.string.field_description), draft.description, stringResource(R.string.profile_description_hint)),
                        Triple(stringResource(R.string.section_appearance).uppercase(), draft.appearance, stringResource(R.string.profile_appearance_hint)),
                    ).forEachIndexed { index, (label, value, hint) ->
                        EditorField(label, value, hint, singleLine = index < 2) { text ->
                            controller.updateDraft(
                                when (index) {
                                    0 -> draft.copy(name = text)
                                    1 -> draft.copy(handle = text)
                                    2 -> draft.copy(description = text)
                                    else -> draft.copy(appearance = text)
                                },
                            )
                        }
                    }
                }
                item {
                    Text(text = stringResource(R.string.section_portrait).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    ImageChoice(
                        title = stringResource(R.string.field_avatar),
                        summary = stringResource(R.string.profile_avatar_summary),
                        image = avatar,
                        revision = mediaRevision,
                        modifier = Modifier.padding(top = 10.dp),
                        onPick = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) {
                        controller.removeAvatar()
                        saved = false
                    }
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = stringResource(
                            when {
                                saving -> R.string.state_saving
                                saved -> R.string.profile_saved
                                else -> R.string.profile_save
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (saving) MaterialTheme.colorScheme.outline else Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickable(enabled = !saving, role = Role.Button) {
                                saving = true
                                scope.launch {
                                    try {
                                        controller.save()
                                        saved = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                            .padding(vertical = 20.dp),
                    )
                }
            }
    }
}
