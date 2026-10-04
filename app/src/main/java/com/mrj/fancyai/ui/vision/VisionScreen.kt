package com.mrj.fancyai.ui.vision

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentDeep
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.vision.VisionModels

@Composable
internal fun VisionScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val responseState = rememberSaveable { mutableStateOf("") }
    val controller = remember(app) { VisionController(app, responseState) }
    val image = controller.image
    val working = controller.working
    val preparingImage = controller.preparingImage
    var lightbox by remember { mutableStateOf(value = false) }
    var modelsOpen by rememberSaveable { mutableStateOf(value = false) }

    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        controller.chooseImage(uri)
        lightbox = false
    }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        controller.importModel(uri)
    }

    LaunchedEffect(controller) {
        controller.load()
    }

    BackHandler {
        if (working) controller.cancel()
        onBack()
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    DisposableEffect(image) {
        onDispose { image?.recycle() }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_vision),
            onBack = {
                if (working) controller.cancel()
                onBack()
            },
            subtitle = stringResource(R.string.vision_eyebrow),
        )
        val model = controller.models.find { (_, path) -> path == controller.selectedPath }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button) { if (!working) modelsOpen = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.vision_model),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    model?.name ?: stringResource(R.string.vision_choose_model),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (model == null) Accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        HorizontalDivider(color = Hairline)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            SourceImage(
                image, preparingImage,
                onChoose = { if (!working && !preparingImage) chooseImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) { if (image != null) lightbox = true }
            controller.ResponsePanel(Modifier.weight(1f))
        }
        controller.VisionComposer { modelsOpen = true }
    }
    if (lightbox) ImageLightbox(image, R.string.vision_selected_image, onClose = { lightbox = false })
    if (modelsOpen) {
        controller.VisionModelsSheet(
            onImport = { importModel.launch(arrayOf("application/octet-stream", "*/*")) },
        ) { if (controller.modelStatus == null) modelsOpen = false }
    }
}

@Composable
private fun SourceImage(
    bitmap: Bitmap?,
    preparing: Boolean,
    onChoose: () -> Unit,
    onView: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.vision_image).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.vision_image_limit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .background(Color.Black, shape)
            .border(1.dp, Hairline, shape)
            .clickable(role = Role.Button, onClick = if (bitmap == null) onChoose else onView),
        contentAlignment = Alignment.Center,
    ) {
        when {
            preparing -> Text(
                stringResource(R.string.vision_preparing_image).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
            )
            bitmap == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.vision_select_image),
                    style = MaterialTheme.typography.titleSmall,
                    color = Accent,
                )
                Text(
                    stringResource(R.string.vision_image_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            else -> {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.vision_selected_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Surface(
                    onClick = onChoose,
                    color = Ink.copy(alpha = 0.92f),
                    contentColor = Accent,
                    shape = RoundedCornerShape(topStart = 12.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).heightIn(min = 48.dp),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.vision_replace_image),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisionController.ResponsePanel(modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(response) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.vision_response),
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
            )
            Spacer(Modifier.weight(1f))
            if (response.isNotBlank()) {
                Text(
                    stringResource(if (copied) R.string.vision_copied else R.string.vision_copy),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = ::copyResponse)
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Slate.copy(alpha = 0.62f))
                .border(1.dp, Hairline)
                .padding(14.dp),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                status?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                error?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                imagePath?.let { path ->
                    com.mrj.fancyai.ui.kit.Artwork(path, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp), contentDescription = stringResource(R.string.aura_result_description))
                }
                if (imagePath == null) MessageMarkdown(
                    response.ifBlank { stringResource(R.string.vision_response_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (response.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun VisionController.VisionComposer(onSetup: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().background(SlateRaised).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PostInput(
            value = prompt,
            hint = stringResource(R.string.vision_question),
            onValueChange = ::updatePrompt,
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f),
        )
        VisionAction(
            label = stringResource(if (working) R.string.action_stop else R.string.vision_project),
            onClick = {
                keyboard?.hide()
                if (working) cancel() else {
                    project()
                    if (selectedPath == null) onSetup()
                }
            },
            color = if (working) Hairline else Accent,
            contentColor = if (working) MaterialTheme.colorScheme.onSurface else Ink,
            modifier = Modifier.width(86.dp).height(56.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionController.VisionModelsSheet(
    onImport: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Slate,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LazyColumn(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.vision_models_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AccentSoft,
                )
                Text(
                    stringResource(R.string.vision_models_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            modelSetup(this@VisionModelsSheet)
            if (models.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.vision_installed_models),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentSoft,
                    )
                }
                items(models.size, key = { models[it].path }) { index ->
                    val model = models[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (model.path == selectedPath) AccentDeep else SlateRaised)
                            .selectable(selected = model.path == selectedPath, enabled = modelStatus == null, role = Role.RadioButton) { selectModel(model) }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (model.path == selectedPath) AccentSoft else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Formatter.formatShortFileSize(
                                    LocalContext.current,
                                    model.sizeBytes,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.action_remove),
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(enabled = modelStatus == null, role = Role.Button) { removeModel(model) }
                                .padding(horizontal = 14.dp, vertical = 16.dp),
                        )
                    }
                }
            }
            item {
                VisionAction(
                    label = stringResource(R.string.vision_import_model),
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    color = Color.Transparent,
                    contentColor = Accent,
                    border = BorderStroke(1.dp, Hairline),
                )
            }
        }
    }
}

@Composable
private fun VisionAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    color: Color = Accent,
    contentColor: Color = Ink,
    border: BorderStroke? = null,
) {
    Surface(
        onClick = onClick,
        color = color,
        contentColor = contentColor,
        border = border,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 4.dp, bottomStart = 12.dp),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.modelSetup(controller: VisionController) {
    with(controller) {
        if (models.none { (name) -> name == VisionModels.RECOMMENDED_FILE_NAME }) {
            item {
                Surface(
                    color = SlateRaised,
                    border = BorderStroke(1.dp, Hairline),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.generation_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                        )
                        Text(
                            stringResource(R.string.vision_recommended_name),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            stringResource(R.string.vision_recommended_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        VisionAction(
                            label = stringResource(
                                R.string.vision_download_recommended,
                                Formatter.formatShortFileSize(LocalContext.current, VisionModels.RECOMMENDED_SIZE_BYTES),
                            ).uppercase(),
                            onClick = ::downloadRecommended,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
                        )
                    }
                }
            }
        }
        modelStatus?.let { operation ->
            item {
                Column(Modifier.fillMaxWidth().background(SlateRaised).padding(12.dp)) {
                    Text(operation, style = MaterialTheme.typography.labelMedium, color = AccentSoft)
                    LinearProgressIndicator(
                        progress = { modelProgress.coerceIn(0f, 1f) },
                        color = Accent,
                        trackColor = Hairline,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
        error?.let { message ->
            item {
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                )
            }
        }
    }
}
