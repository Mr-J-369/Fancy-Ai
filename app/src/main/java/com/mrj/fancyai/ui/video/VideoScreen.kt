package com.mrj.fancyai.ui.video

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.faceswap.FaceSwapChooserDialog
import com.mrj.fancyai.ui.faceswap.FaceSwapGalleryPicker
import com.mrj.fancyai.ui.gallery.GalleryScreen
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.VideoLightbox
import com.mrj.fancyai.ui.kit.VideoPlayer
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun VideoGenerationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(app, scope) { VideoController(app, scope) }

    var galleryOpen by remember { mutableStateOf(false) }
    var fullscreenVideo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var modelDetails by remember { mutableStateOf<VideoModelInfo?>(null) }
    var frameChooserFor by remember { mutableStateOf<VideoFrameSlot?>(null) }
    var appFramePickerFor by remember { mutableStateOf<VideoFrameSlot?>(null) }
    var pendingPickSlot by remember { mutableStateOf<VideoFrameSlot?>(null) }

    val exportedMessage = stringResource(R.string.video_exported)
    val deletedMessage = stringResource(R.string.video_deleted)
    val copiedMessage = stringResource(R.string.aura_details_copied)

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val slot = pendingPickSlot.also { pendingPickSlot = null }
        if (slot != null) controller.pickFrameFromUri(slot, uri)
    }
    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) {
            controller.exportResult(uri)
            scope.launch { snackbar.showSnackbar(exportedMessage) }
        }
    }

    LaunchedEffect(Unit) { controller.refreshGalleryImages() }
    LaunchedEffect(controller.state.provider) {
        if (controller.state.models.isEmpty() && !controller.state.loadingModels) {
            controller.fetchModels()
        }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }

    BackHandler {
        when {
            confirmDelete -> confirmDelete = false
            modelDetails != null -> modelDetails = null
            appFramePickerFor != null -> appFramePickerFor = null
            frameChooserFor != null -> frameChooserFor = null
            fullscreenVideo -> fullscreenVideo = false
            galleryOpen -> galleryOpen = false
            else -> controller.navigateBack(onBack)
        }
    }

    frameChooserFor?.let { slot ->
        FaceSwapChooserDialog(
            onPhoneGallery = {
                frameChooserFor = null
                pendingPickSlot = slot
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onAppGallery = {
                frameChooserFor = null
                controller.refreshGalleryImages()
                appFramePickerFor = slot
            },
            onDismiss = { frameChooserFor = null },
        )
    }

    appFramePickerFor?.let { slot ->
        FaceSwapGalleryPicker(
            images = controller.state.galleryImages,
            onPick = { file ->
                controller.pickFrameFromGallery(slot, file)
                appFramePickerFor = null
            },
            onDismiss = { appFramePickerFor = null },
        )
    }

    modelDetails?.let { model ->
        VideoModelDetailsDialog(
            model = model,
            onSelect = {
                controller.selectModel(model)
                controller.page = VideoPage.STUDIO
                modelDetails = null
            },
            onDismiss = { modelDetails = null },
        )
    }

    if (confirmDelete) {
        AppDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.video_delete_title)) },
            text = { Text(stringResource(R.string.video_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    controller.deleteResult()
                    scope.launch { snackbar.showSnackbar(deletedMessage) }
                }) {
                    Text(stringResource(R.string.action_delete), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val resultFile = controller.state.resultFile
    if (fullscreenVideo && resultFile != null) {
        VideoLightbox(file = resultFile, onClose = { fullscreenVideo = false })
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surfaceContainerLow,
                        0.28f to Ink,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AppHeader(
                title = stringResource(R.string.home_app_video_generation),
                subtitle = stringResource(R.string.video_eyebrow),
                onBack = { controller.navigateBack(onBack) },
            )
            VideoNavigation(
                page = controller.page,
                onOpenGallery = { galleryOpen = true },
                onPage = { controller.page = it },
            )
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                when (controller.page) {
                    VideoPage.STUDIO -> VideoStudioPage(
                        controller = controller,
                        fullscreenActive = fullscreenVideo,
                        onOpenModels = { controller.page = VideoPage.MODELS },
                        onOpenModelDetails = { modelDetails = it },
                        onPickFrame = { slot ->
                            controller.refreshGalleryImages()
                            frameChooserFor = slot
                        },
                        onFullscreen = { fullscreenVideo = true },
                        onShare = { controller.shareResult(context) },
                        onExport = { file -> exportVideo.launch(file.name) },
                        onRequestDelete = { confirmDelete = true },
                        onCopiedDetails = { scope.launch { snackbar.showSnackbar(copiedMessage) } },
                    )
                    VideoPage.MODELS -> VideoModelsPage(
                        controller = controller,
                        onOpenModelDetails = { modelDetails = it },
                        onModelChosen = { controller.page = VideoPage.STUDIO },
                    )
                }
            }
            controller.state.error?.let { error ->
                Text(
                    text = error,
                    color = Danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            VideoDock(controller)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }

    if (galleryOpen) {
        GalleryScreen(onBack = {
            galleryOpen = false
            controller.refreshGalleryImages()
        })
    }
}

@Composable
private fun VideoNavigation(
    page: VideoPage,
    onOpenGallery: () -> Unit,
    onPage: (VideoPage) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        VideoPage.entries.forEach { item ->
            Text(
                text = stringResource(item.label),
                style = MaterialTheme.typography.labelMedium,
                color = if (item == page) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onPage(item) }
                    .padding(vertical = 15.dp),
            )
        }
        Text(
            text = stringResource(R.string.home_app_gallery),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onOpenGallery)
                .padding(vertical = 15.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun VideoSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AccentSoft,
        )
        content()
    }
}

@Composable
private fun VideoStudioPage(
    controller: VideoController,
    fullscreenActive: Boolean,
    onOpenModels: () -> Unit,
    onOpenModelDetails: (VideoModelInfo) -> Unit,
    onPickFrame: (VideoFrameSlot) -> Unit,
    onFullscreen: () -> Unit,
    onShare: () -> Unit,
    onExport: (File) -> Unit,
    onRequestDelete: () -> Unit,
    onCopiedDetails: () -> Unit,
) {
    val state = controller.state
    val model = state.selectedModel
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.resultFile?.let { file ->
            VideoResultSection(
                file = file,
                metadata = state.resultMetadata,
                fullscreenActive = fullscreenActive,
                onFullscreen = onFullscreen,
                onShare = onShare,
                onExport = { onExport(file) },
                onDelete = onRequestDelete,
                onCopyDetails = {
                    controller.copyResultDetails()
                    onCopiedDetails()
                },
            )
        }

        VideoSection(stringResource(R.string.video_model_section)) {
            if (model == null) {
                Text(
                    text = stringResource(R.string.video_choose_model),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.aura_open_models))
                }
            } else {
                val detailParts = listOfNotNull(
                    stringResource(state.provider.label),
                    model.pricingSummary.takeIf(String::isNotBlank),
                    model.capabilitySummary().takeIf(String::isNotBlank),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(SlateRaised)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .clickable(onClick = onOpenModels)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).background(Accent))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            detailParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    TextButton(onClick = { onOpenModelDetails(model) }) {
                        Text(stringResource(R.string.action_details), color = AccentSoft)
                    }
                }
            }
        }

        VideoSection(stringResource(R.string.video_prompt_section)) {
            PostInput(
                value = state.prompt,
                onValueChange = controller::updatePrompt,
                label = stringResource(R.string.video_prompt),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (model?.supportsNegativePrompt == true) {
                PostInput(
                    value = state.negativePrompt,
                    onValueChange = controller::updateNegativePrompt,
                    label = stringResource(R.string.aura_negative_prompt),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (model == null || model.supportsFirstFrame) {
            VideoSection(stringResource(R.string.video_frames_section)) {
                Text(
                    text = stringResource(
                        if (model != null && !model.supportsTextToVideo) R.string.video_frame_required_note
                        else R.string.video_frames_note,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FrameSlotCard(
                        label = stringResource(R.string.video_first_frame),
                        preview = state.firstFramePreview,
                        onPick = { onPickFrame(VideoFrameSlot.FIRST) },
                        onClear = { controller.clearFrame(VideoFrameSlot.FIRST) },
                        modifier = Modifier.weight(1f),
                    )
                    if (model?.supportsLastFrame == true) {
                        FrameSlotCard(
                            label = stringResource(R.string.video_last_frame),
                            preview = state.lastFramePreview,
                            onPick = { onPickFrame(VideoFrameSlot.LAST) },
                            onClear = { controller.clearFrame(VideoFrameSlot.LAST) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (model != null) {
            VideoParametersSection(controller = controller, model = model)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VideoResultSection(
    file: File,
    metadata: VideoGenerationMetadata?,
    fullscreenActive: Boolean,
    onFullscreen: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCopyDetails: () -> Unit,
) {
    VideoSection(stringResource(R.string.video_result_section)) {
        val ratio = metadata?.let {
            if (it.width > 0 && it.height > 0) (it.width.toFloat() / it.height).coerceIn(0.5f, 2.2f) else null
        } ?: (16f / 9f)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black),
        ) {
            if (!fullscreenActive) {
                VideoPlayer(
                    file = file,
                    modifier = Modifier.fillMaxSize(),
                    autoPlay = true,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onFullscreen) {
                Text(stringResource(R.string.video_fullscreen))
            }
            OutlinedButton(onClick = onShare) {
                Text(stringResource(R.string.action_share))
            }
            OutlinedButton(onClick = onExport) {
                Text(stringResource(R.string.action_export))
            }
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        }
        Text(
            text = stringResource(R.string.video_saved_to_gallery),
            style = MaterialTheme.typography.bodySmall,
            color = AccentSoft,
        )
        metadata?.let { meta ->
            val summary = listOfNotNull(
                meta.cloudProvider.takeIf(String::isNotBlank),
                meta.model.takeIf(String::isNotBlank),
                meta.durationSeconds?.let { stringResource(R.string.video_duration_seconds, it) },
                meta.resolution.takeIf(String::isNotBlank),
                meta.aspectRatio.takeIf(String::isNotBlank),
                if (meta.width > 0 && meta.height > 0) "${meta.width}×${meta.height}" else null,
                meta.seed?.let { "${stringResource(R.string.field_seed)}: $it" },
                meta.costUsd?.let { stringResource(R.string.video_cost_reported, VideoCloudClient.formatUsdValue(it)) },
            ).joinToString(" · ")
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            meta.generationDurationMs?.let { durationMs ->
                Text(
                    text = stringResource(
                        R.string.aura_generation_duration,
                        DateUtils.formatElapsedTime((durationMs + 500L) / 1000L),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentSoft,
                )
            }
            TextButton(onClick = onCopyDetails) {
                Text(stringResource(R.string.aura_copy_details), color = AccentSoft)
            }
        }
    }
}

@Composable
private fun FrameSlotCard(
    label: String,
    preview: Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(SlateRaised)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onPick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.video_choose_frame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (preview != null) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.video_clear_frame), color = Danger)
            }
        }
    }
}

@Composable
private fun VideoParametersSection(
    controller: VideoController,
    model: VideoModelInfo,
) {
    val state = controller.state
    val durations = model.durationOptions()
    val usesSizes = model.supportedSizes.isNotEmpty() ||
        (model.supportedResolutions.isEmpty() && model.supportedAspectRatios.isEmpty())

    VideoSection(stringResource(R.string.video_parameters_section)) {
        if (durations.isNotEmpty() || model.minDuration != null) {
            var durationText by remember(model.id) {
                mutableStateOf(state.durationSeconds?.toString().orEmpty())
            }
            LaunchedEffect(state.durationSeconds) {
                val expected = state.durationSeconds?.toString().orEmpty()
                if (durationText != expected) durationText = expected
            }
            Text(
                stringResource(R.string.video_duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (durations.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    durations.forEach { sec ->
                        FilterChip(
                            selected = state.durationSeconds == sec,
                            onClick = { controller.updateDuration(sec) },
                            label = { Text(stringResource(R.string.video_duration_seconds, sec)) },
                        )
                    }
                }
            }
            PostInput(
                value = durationText,
                onValueChange = { candidate ->
                    val digits = candidate.filter(Char::isDigit)
                    durationText = digits
                    controller.updateDuration(digits.toIntOrNull()?.takeIf { it > 0 })
                },
                label = stringResource(R.string.video_duration),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (usesSizes) {
            Text(
                stringResource(R.string.aura_dimensions_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.supportedSizes.isNotEmpty()) {
                val currentSize = "${state.width}x${state.height}"
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.supportedSizes.forEach { size ->
                        FilterChip(
                            selected = currentSize == size,
                            onClick = { controller.selectSize(size) },
                            label = { Text(size) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PostInput(
                    value = state.width,
                    onValueChange = controller::updateWidth,
                    label = stringResource(R.string.aura_width),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                PostInput(
                    value = state.height,
                    onValueChange = controller::updateHeight,
                    label = stringResource(R.string.aura_height),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (model.supportedResolutions.isNotEmpty()) {
            Text(
                stringResource(R.string.video_resolution),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.supportedResolutions.forEach { res ->
                    FilterChip(
                        selected = state.resolution.equals(res, ignoreCase = true),
                        onClick = { controller.updateResolution(res) },
                        label = { Text(res) },
                    )
                }
            }
            PostInput(
                value = state.resolution,
                onValueChange = controller::updateResolution,
                label = stringResource(R.string.video_resolution),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (model.supportedAspectRatios.isNotEmpty()) {
            Text(
                stringResource(R.string.video_aspect_ratio),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                model.supportedAspectRatios.forEach { ratio ->
                    FilterChip(
                        selected = state.aspectRatio == ratio,
                        onClick = { controller.updateAspectRatio(ratio) },
                        label = { Text(ratio) },
                    )
                }
            }
            PostInput(
                value = state.aspectRatio,
                onValueChange = controller::updateAspectRatio,
                label = stringResource(R.string.video_aspect_ratio),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (model.supportsAudio) {
            CompactSwitchRow(
                title = stringResource(R.string.video_generate_audio),
                summary = stringResource(R.string.video_generate_audio_summary),
                checked = state.generateAudio,
                onCheckedChange = controller::updateAudio,
            )
        }

        if (model.supportsSeed) {
            PostInput(
                value = state.seed,
                onValueChange = { candidate ->
                    if (candidate.all { it.isDigit() || it == '-' }) controller.updateSeed(candidate)
                },
                label = stringResource(R.string.field_seed),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = controller::toggleSeedLock,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (state.seedLocked) R.string.aura_unlock_seed else R.string.aura_lock_seed))
                }
                OutlinedButton(
                    onClick = controller::rollSeed,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dice),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.aura_random_seed))
                }
            }
        }

        model.estimateUsd(state.durationSeconds)?.let { estimated ->
            Text(
                text = stringResource(R.string.video_estimated_cost, VideoCloudClient.formatUsdValue(estimated)),
                style = MaterialTheme.typography.bodySmall,
                color = AccentSoft,
            )
        }
    }
}

@Composable
private fun VideoModelsPage(
    controller: VideoController,
    onOpenModelDetails: (VideoModelInfo) -> Unit,
    onModelChosen: () -> Unit,
) {
    val state = controller.state
    val matches = controller.filteredModels
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            VideoSection(stringResource(R.string.video_provider_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VideoProvider.entries.forEach { provider ->
                        FilterChip(
                            selected = state.provider == provider,
                            onClick = { controller.selectProvider(provider) },
                            enabled = !state.generating,
                            label = { Text(stringResource(provider.label)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                PostInput(
                    value = state.apiKey,
                    onValueChange = controller::updateApiKey,
                    label = stringResource(R.string.cloud_api_key),
                    singleLine = true,
                    secret = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        if (state.provider == VideoProvider.OPENROUTER) R.string.cloud_openrouter_key_summary
                        else R.string.cloud_deepinfra_key_summary,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cloud_models_section),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentSoft,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (state.loadingModels) R.string.cloud_loading_models else R.string.cloud_refresh_models,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.loadingModels) MaterialTheme.colorScheme.outline else Accent,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !state.loadingModels, role = Role.Button, onClick = controller::fetchModels)
                        .padding(start = 12.dp, top = 14.dp),
                )
            }
            if (state.loadingModels) {
                LinearProgressIndicator(
                    color = Accent,
                    trackColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            state.modelsError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (state.models.isEmpty() && !state.loadingModels) {
            item {
                Text(
                    text = stringResource(R.string.video_refresh_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.models.isNotEmpty()) {
            item {
                PostInput(
                    value = state.query,
                    onValueChange = controller::updateQuery,
                    label = stringResource(R.string.cloud_search_models),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = pluralStringResource(R.plurals.cloud_models_found, matches.size, matches.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(matches, key = { it.id }) { candidate ->
                VideoModelRow(
                    model = candidate,
                    selected = state.selectedModel?.id == candidate.id,
                    onSelect = {
                        controller.selectModel(candidate)
                        onModelChosen()
                    },
                    onDetails = { onOpenModelDetails(candidate) },
                )
            }
        }
    }
}

@Composable
private fun VideoModelRow(
    model: VideoModelInfo,
    selected: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    val summary = listOfNotNull(
        model.pricingSummary.takeIf(String::isNotBlank),
        model.capabilitySummary().takeIf(String::isNotBlank),
    ).joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) AccentSoft else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.displayName != model.id) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (model.pricingSummary.isNotBlank()) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.padding(horizontal = 4.dp).size(18.dp),
            )
        }
        TextButton(onClick = onDetails) {
            Text(stringResource(R.string.action_details), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VideoModelDetailsDialog(
    model: VideoModelInfo,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.description.isNotBlank()) {
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.video_capabilities_section),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                )
                val modes = listOfNotNull(
                    stringResource(R.string.video_supports_t2v).takeIf { model.supportsTextToVideo },
                    stringResource(R.string.video_supports_i2v).takeIf { model.supportsFirstFrame },
                    stringResource(R.string.video_supports_audio).takeIf { model.supportsAudio },
                ).joinToString(" · ")
                if (modes.isNotBlank()) {
                    Text(modes, style = MaterialTheme.typography.bodySmall)
                }
                val durations = model.durationOptions()
                if (durations.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.video_duration)}: ${durations.joinToString(", ") { "${it}s" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (model.minDuration != null && model.maxDuration != null) {
                    Text(
                        "${stringResource(R.string.video_duration)}: ${model.minDuration}–${model.maxDuration}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.supportedResolutions.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.video_resolution)}: ${model.supportedResolutions.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.supportedSizes.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.aura_dimensions_section)}: ${model.supportedSizes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.supportedAspectRatios.isNotEmpty()) {
                    Text(
                        "${stringResource(R.string.video_aspect_ratio)}: ${model.supportedAspectRatios.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = stringResource(R.string.video_pricing_section),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                )
                when {
                    model.pricingDetails.isNotEmpty() -> model.pricingDetails.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            Text(entry.price, style = MaterialTheme.typography.bodySmall, color = AccentSoft)
                        }
                    }
                    model.pricingSummary.isNotBlank() -> Text(
                        model.pricingSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentSoft,
                    )
                    else -> Text(
                        stringResource(R.string.video_no_pricing_details),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSelect) {
                Text(stringResource(R.string.cloud_use_provider), color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun VideoDock(controller: VideoController) {
    val state = controller.state
    val model = state.selectedModel
    val canGenerate = model != null &&
        (state.prompt.isNotBlank() || (model.supportsFirstFrame && state.firstFrameFile != null)) &&
        (model.supportsTextToVideo || state.firstFrameFile != null)

    Column(
        Modifier
            .fillMaxWidth()
            .background(SlateRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.generating) {
            val stageRes = when (state.statusStage) {
                VideoStatusStage.SUBMITTING, null -> R.string.video_submitting
                VideoStatusStage.POLLING -> R.string.video_polling
                VideoStatusStage.DOWNLOADING -> R.string.video_downloading
            }
            Text(
                text = stringResource(stageRes),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
            )
            LinearProgressIndicator(
                color = Accent,
                trackColor = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
        Button(
            onClick = { if (state.generating) controller.stop() else controller.generate() },
            enabled = state.generating || canGenerate,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.generating) MaterialTheme.colorScheme.surface else Accent,
                contentColor = if (state.generating) Accent else Ink,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                stringResource(if (state.generating) R.string.action_stop else R.string.video_create),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
