package com.mrj.fancyai.ui.faceswap

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.util.decodeImage
import java.io.File

@Composable
internal fun FaceSwapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(context) { FaceSwapController(context, scope) }
    var lightboxFile by remember { mutableStateOf<File?>(null) }
    var chooserFor by remember { mutableStateOf<FaceSwapSlot?>(null) }
    var appPickerFor by remember { mutableStateOf<FaceSwapSlot?>(null) }

    LaunchedEffect(Unit) { controller.refresh() }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        controller.onUriPicked(FaceSwapSlot.SOURCE, it)
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        controller.onUriPicked(FaceSwapSlot.TARGET, it)
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    BackHandler {
        when {
            lightboxFile != null -> lightboxFile = null
            appPickerFor != null -> appPickerFor = null
            chooserFor != null -> chooserFor = null
            else -> onBack()
        }
    }

    if (controller.needsConsent) {
        FaceSwapConsentDialog(onConfirm = { controller.acceptConsent() }, onDismiss = onBack)
        return
    }

    chooserFor?.let { slot ->
        FaceSwapChooserDialog(
            onPhoneGallery = {
                chooserFor = null
                if (slot == FaceSwapSlot.SOURCE) sourcePicker.launch(imageOnly)
                else targetPicker.launch(imageOnly)
            },
            onAppGallery = { appPickerFor = slot; chooserFor = null },
        ) { chooserFor = null }
    }

    appPickerFor?.let { slot ->
        FaceSwapGalleryPicker(
            images = controller.galleryImages,
            onPick = { file ->
                runCatching { decodeImage(context, Uri.fromFile(file), 1024) }.getOrNull()
                    ?.let { controller.pickBitmap(slot, it) }
                appPickerFor = null
            },
        ) { appPickerFor = null }
    }

    Box(Modifier.fillMaxSize()) {
        FaceSwapBody(
            controller = controller,
            onBack = onBack,
            onPickSlot = { chooserFor = it },
        ) { lightboxFile = it }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    lightboxFile?.let { FaceSwapLightbox(file = it) { lightboxFile = null } }
}

@Composable
private fun FaceSwapLightbox(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val full by produceState<Bitmap?>(initialValue = null, file) {
        value = runCatching { decodeImage(context, Uri.fromFile(file), 2048) }.getOrNull()
    }
    ImageLightbox(bitmap = full, onClose = onClose)
}

@Composable
private fun FaceSwapBody(
    controller: FaceSwapController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickSlot: (FaceSwapSlot) -> Unit,
    onResultClick: (File) -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppHeader(
            title = stringResource(R.string.faceswap_title),
            subtitle = stringResource(R.string.faceswap_local),
            onBack = onBack,
        )
            if (!controller.modelsInstalled) {
                FaceSwapModelsCard(controller = controller)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhotoSlot(
                    label = stringResource(R.string.faceswap_source_face),
                    bitmap = controller.sourceBitmap,
                    onPick = { onPickSlot(FaceSwapSlot.SOURCE) },
                    modifier = Modifier.weight(1f),
                )
                PhotoSlot(
                    label = stringResource(R.string.faceswap_target_photo),
                    bitmap = controller.targetBitmap,
                    onPick = { onPickSlot(FaceSwapSlot.TARGET) },
                    modifier = Modifier.weight(1f),
                )
            }
            FaceSwapOptions(controller = controller)
            Button(
                onClick = { controller.run() },
                enabled = (!controller.busy) && (controller.sourceBitmap != null) &&
                    (controller.targetBitmap != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (controller.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.faceswap_swapping))
                } else {
                    Text(stringResource(R.string.faceswap_run))
                }
            }
            controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            controller.resultFile?.let { file ->
                FaceSwapResult(file = file, onEnlarge = onResultClick)
            }
        }
}

@Composable
private fun FaceSwapModelsCard(controller: FaceSwapController) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.faceswap_models_description))
            if (controller.downloading) {
                LinearProgressIndicator(
                    progress = { controller.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = { controller.installModels() }) {
                    Text(stringResource(R.string.faceswap_download_models))
                }
            }
        }
    }
}

@Composable
private fun FaceSwapOptions(controller: FaceSwapController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.faceswap_swap_all_faces))
            Text(
                stringResource(R.string.faceswap_swap_all_faces_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = controller.allFaces, onCheckedChange = { controller.onAllFaces(it) })
    }
    Column {
        Text(
            "${stringResource(R.string.faceswap_restore_fidelity)}: " +
                "%.2f".format(controller.fidelity),
        )
        Slider(
            value = controller.fidelity,
            onValueChange = { controller.onFidelity(it) },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun FaceSwapResult(file: File, onEnlarge: (File) -> Unit) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(initialValue = null, file) {
        value = runCatching { decodeImage(context, Uri.fromFile(file), 1024) }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.faceswap_saved),
            color = MaterialTheme.colorScheme.primary,
        )
        preview?.let { bitmap ->
            Image(
                bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.faceswap_result),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onEnlarge(file) },
            )
        }
    }
}

@Composable
private fun PhotoSlot(label: String, bitmap: Bitmap?, onPick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier, onClick = onPick) {
        Column(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        stringResource(R.string.faceswap_choose_photo),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
