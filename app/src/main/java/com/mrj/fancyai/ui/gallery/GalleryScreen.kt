package com.mrj.fancyai.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date


@Composable
internal fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(context) { GalleryController(context) }
    with(controller) {

        val generatedTitle = stringResource(R.string.gallery_generated)
        val importedTitle = stringResource(R.string.gallery_imported)
        val exported = stringResource(R.string.gallery_exported)
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val source = exporting.also { exporting = null }
            if ((uri != null) && (source != null)) scope.launch {
                withContext(Dispatchers.IO) { exportDocument(context, uri) { output -> source.inputStream().use { it.copyTo(output) } } }
                notice = exported
            }
        }

        LaunchedEffect(revision, location, context, generatedTitle, importedTitle) {
            load(generatedTitle, importedTitle)
        }
        LaunchedEffect(viewing?.absolutePath, viewing?.lastModified()) {
            val file = viewing ?: return@LaunchedEffect
            fullImage = withContext(Dispatchers.IO) { decodeImage(context, Uri.fromFile(file), VIEW_EDGE) }
        }
        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                notice = null
            }
        }
        BackHandler {
            when {
                viewing != null -> viewing = null
                selected.isNotEmpty() -> selected = emptySet()
                location != null -> leaveFolder()
                else -> onBack()
            }
        }

        GallerySurface(controller, snackbar, onBack)
        GalleryViewer(controller, context, scope, snackbar) { file ->
            exporting = file
            export.launch(file.name)
        }
    }
    GalleryDialogs(controller, context, scope)
}

@Composable
private fun GalleryViewer(
    controller: GalleryController,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    onExport: (File) -> Unit,
) = with(controller) {
    viewing?.let { file ->
        val imageIndex = contents.images.indexOfFirst { it.absolutePath == file.absolutePath }
        ImageLightbox(
            bitmap = fullImage,
            description = R.string.gallery_image_preview,
            onClose = { viewing = null },
            onPrevious = contents.images.getOrNull(imageIndex - 1)?.let { previous ->
                {
                    fullImage = null
                    viewing = previous
                }
            },
            onNext = contents.images.getOrNull(imageIndex + 1)?.let { next ->
                {
                    fullImage = null
                    viewing = next
                }
            },
        ) {
            ViewerChrome(
                position = imageIndex + 1,
                total = contents.images.size,
                snackbar = snackbar,
                onShare = { shareImage(context, file) },
                onDetails = {
                    scope.launch {
                        details = withContext(Dispatchers.IO) { readDetails(file) }
                    }
                },
                onExport = { onExport(file) },
            ) {
                viewing = null
                deleting = listOf(file)
            }
        }
    }
}

@Composable
private fun GallerySurface(
    controller: GalleryController,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) = with(controller) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(SlateRaised, Ink, MaterialTheme.colorScheme.background)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            GalleryHeader(
                title = location?.let { if (it.directory.canonicalFile == it.root.canonicalFile) it.title else it.directory.name } ?: stringResource(R.string.home_app_gallery),
                imageCount = contents.imageCount,
                selectedCount = selected.size,
                canSelectAll = selected.size < contents.images.size,
                onBack = when {
                    selected.isNotEmpty() -> ({ selected = emptySet() })
                    location != null -> ::leaveFolder
                    else -> onBack
                },
                onSelectAll = { selected = contents.images.mapTo(mutableSetOf(), File::getAbsolutePath) },
            ) {
                deleting = contents.images.filter { it.absolutePath in selected }
            }
            HorizontalDivider(color = Hairline)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    loading -> CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.align(Alignment.Center).requiredSize(24.dp),
                    )
                    location == null -> AlbumGrid(albums) { album ->
                        contents = GalleryContents()
                        location = GalleryLocation(album.title, album.directory, album.directory, album.foldersVisible)
                    }
                    contents.folders.isEmpty() && contents.images.isEmpty() -> EmptyFolder(Modifier.align(Alignment.Center))
                    else -> FolderGrid(
                        folders = contents.folders,
                        images = contents.images,
                        selected = selected,
                        onFolder = { folder ->
                            contents = GalleryContents()
                            location = checkNotNull(location).copy(directory = folder.directory)
                        },
                        onImage = { file ->
                            if (selected.isEmpty()) {
                                fullImage = null
                                viewing = file
                            } else selected = if (file.absolutePath in selected) selected - file.absolutePath else selected + file.absolutePath
                        },
                    ) { file ->
                        selected = if (file.absolutePath in selected) selected - file.absolutePath else selected + file.absolutePath
                    }
                }
            }
        }
        if (viewing == null) SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
private fun GalleryDialogs(
    controller: GalleryController,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) = with(controller) {
    details?.let { value ->
        DetailsDialog(
            details = value,
            onCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(value.file.name, text))
                notice = context.getString(R.string.gallery_details_copied)
            },
            onApply = {
                applyGalleryDetailsToAura(context, value)
                details = null
                notice = context.getString(R.string.gallery_applied_to_aura)
            },
        ) { details = null }
    }
    if (deleting.isNotEmpty()) {
        val count = deleting.size
        val deleted = pluralStringResource(R.plurals.gallery_deleted, count, count)
        AppDialog(
            onDismissRequest = { deleting = emptyList() },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.gallery_delete_message, count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val candidates = deleting.also { deleting = emptyList() }
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { deleteGalleryImages(candidates) }
                                selected = emptySet()
                                viewing = null
                                notice = deleted
                            } finally {
                                revision++
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = emptyList() }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
            },
        )
    }
}

@Composable
private fun GalleryHeader(
    title: String,
    imageCount: Int,
    selectedCount: Int,
    canSelectAll: Boolean,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleteLabel = stringResource(R.string.action_delete)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backLabel = stringResource(
            if (selectedCount == 0) R.string.settings_back else R.string.gallery_clear_selection,
        )
        Box(
            Modifier.size(48.dp).semantics { contentDescription = backLabel }
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(painter = painterResource(if (selectedCount == 0) R.drawable.ic_back else R.drawable.ic_close), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (selectedCount == 0) title else pluralStringResource(
                    R.plurals.gallery_selected,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.gallery_images, imageCount, imageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selectedCount > 0) {
            if (canSelectAll) TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.gallery_select_all), color = AccentSoft)
            }
            Box(
                Modifier.size(48.dp).semantics { contentDescription = deleteLabel }
                    .clickable(role = Role.Button, onClick = onDelete),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun BoxScope.ViewerChrome(
    position: Int,
    total: Int,
    snackbar: SnackbarHostState,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(value = false) }
    val actionsLabel = stringResource(R.string.gallery_image_actions)
    Box(
        Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 4.dp),
    ) {
        Box(
            Modifier.size(48.dp).semantics { contentDescription = actionsLabel }
                .clickable(role = Role.Button) { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_more_horizontal), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = SlateRaised) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share), style = MaterialTheme.typography.labelMedium) },
                onClick = {
                    menuOpen = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_details), style = MaterialTheme.typography.labelMedium) },
                onClick = {
                    menuOpen = false
                    onDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_export), style = MaterialTheme.typography.labelMedium) },
                onClick = {
                    menuOpen = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
    Surface(
        color = Ink.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 8.dp),
    ) {
        Text(
            stringResource(R.string.gallery_position, position, total),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
    )
}

@Composable
internal fun DetailsDialog(details: GalleryDetails, onCopy: (String) -> Unit, onApply: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val rows = buildList {
        add(stringResource(R.string.files_file_type) to details.file.name)
        add(stringResource(R.string.gallery_details_size) to Formatter.formatShortFileSize(context, details.file.length()))
        add(stringResource(R.string.gallery_details_dimensions) to stringResource(R.string.gallery_dimensions, details.width, details.height))
        add(stringResource(R.string.files_created) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(details.file.lastModified())))
        details.prompt.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.gallery_details_prompt) to it) }
        details.negativePrompt.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.aura_negative_prompt) to it) }
        details.model.takeIf(String::isNotBlank)?.let {
            add(stringResource(R.string.label_model).uppercase() to it)
        }
        details.sampler.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.aura_sampler) to it) }
        details.steps?.let { add(stringResource(R.string.aura_steps) to it.toString()) }
        details.cfg?.let {
            add(
                stringResource(R.string.gallery_details_cfg) to
                    stringResource(R.string.format_decimal_two_places, it),
            )
        }
        details.seed?.let { add(stringResource(R.string.field_seed) to it.toString()) }
    }
    val copyFormat = stringResource(R.string.format_label_value)
    val copyText = rows.joinToString("\n") { (label, value) -> String.format(locale, copyFormat, label, value) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_details), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                rows.forEach { (label, value) ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Accent)
                    Text(value, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) { Text(stringResource(R.string.gallery_apply_to_aura)) }
            TextButton(onClick = { onCopy(copyText) }) { Text(stringResource(R.string.aura_copy_details)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_close)) } },
    )
}
