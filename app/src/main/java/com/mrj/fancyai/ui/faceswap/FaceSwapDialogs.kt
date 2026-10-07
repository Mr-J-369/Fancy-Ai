package com.mrj.fancyai.ui.faceswap

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.util.decodeImage
import java.io.File

@Composable
internal fun FaceSwapConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.faceswap_consent_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        title = { Text(stringResource(R.string.faceswap_consent_title)) },
        text = { Text(stringResource(R.string.faceswap_consent_body)) },
    )
}

@Composable
internal fun FaceSwapChooserDialog(
    onPhoneGallery: () -> Unit,
    onAppGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        title = { Text(stringResource(R.string.faceswap_choose_from)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPhoneGallery, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.faceswap_phone_gallery))
                }
                OutlinedButton(onClick = onAppGallery, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.faceswap_gallery_title))
                }
            }
        },
    )
}

@Composable
internal fun FaceSwapGalleryPicker(
    images: List<File>,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.faceswap_gallery_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (images.isEmpty()) {
                    Text(
                        stringResource(R.string.faceswap_gallery_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 360.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(images) { file ->
                            GalleryThumb(file = file) { onPick(file) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryThumb(file: File, onPick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, file) {
        value = runCatching { decodeImage(context, Uri.fromFile(file), 256) }.getOrNull()
    }
    val bitmap = thumb
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPick),
        )
    } else {
        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
    }
}
