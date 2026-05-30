package com.mrj.fancyai.ui.imaging

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mrj.fancyai.ui.components.ImageLightbox
import com.mrj.fancyai.util.MediaActions
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImagingScreen(
    navController: NavHostController,
    viewModel: ImagingViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var lightboxImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.setSourceImage(context.contentResolver.openInputStream(it))
        }
    }

    val generated = viewModel.generatedImage
    lightboxImage?.let { img ->
        ImageLightbox(
            model = img,
            onDismiss = { lightboxImage = null },
            onSave = {
                scope.launch {
                    val ok = MediaActions.saveBitmapToGallery(context, img, "fancyai_${System.currentTimeMillis()}")
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Saved to gallery" else "Save failed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onShare = {
                scope.launch { MediaActions.shareBitmap(context, img, "fancyai_${System.currentTimeMillis()}") }
            }
        )
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Imaging Studio", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Clear Studio")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Pipeline Selection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(false to "Forge / A1111", true to "Local Dream (NPU)").forEach { (value, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.updateUseLocalDream(value) }
                            .background(if (viewModel.useLocalDream == value) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (viewModel.useLocalDream == value) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Prompts
            Text("Generation Prompt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = viewModel.prompt,
                onValueChange = { viewModel.updatePrompt(it) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Describe the target frame details...") },
                shape = RoundedCornerShape(16.dp)
            )
            
            if (viewModel.useLocalDream) {
                Text(
                    "Tokens: ${viewModel.tokenCount}/77",
                    fontSize = 10.sp,
                    color = if (viewModel.tokenCount > 77) Color.Red else Color.Gray,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Studio Parameters Matrix
            StudioParameterMatrix(viewModel)

            Spacer(modifier = Modifier.height(24.dp))

            // img2img Source
            if (viewModel.sourceImage == null) {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Source Image (img2img)")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Source Image Attached", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearSourceImage() }) {
                        Text("Clear", color = Color.Red)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    AsyncImage(
                        model = viewModel.sourceImage,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("Denoising Strength: ${String.format("%.2f", viewModel.denoisingStrength)}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = viewModel.denoisingStrength,
                    onValueChange = { viewModel.updateDenoising(it) },
                    valueRange = 0.05f..1.0f
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Render Output Matrix
            if (viewModel.isGenerating) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (viewModel.progress > 0) {
                        LinearProgressIndicator(
                            progress = { viewModel.progress / 100f },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("${viewModel.progress}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Starting…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            } else if (viewModel.generatedImage != null) {
                Text("Studio Output Matrix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                if (viewModel.sourceImage != null) {
                    // Compare View
                    Row(modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Color.Gray, RoundedCornerShape(16.dp))) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable { lightboxImage = viewModel.sourceImage }) {
                            AsyncImage(model = viewModel.sourceImage, contentDescription = "Tap to enlarge", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Surface(color = Color.Black.copy(0.6f), modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(4.dp)) {
                                Text("BEFORE", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.Gray))
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable { lightboxImage = viewModel.generatedImage }) {
                            AsyncImage(model = viewModel.generatedImage, contentDescription = "Tap to enlarge", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Surface(color = Color.Black.copy(0.6f), modifier = Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(4.dp)) {
                                Text("AFTER", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .clickable { lightboxImage = viewModel.generatedImage }
                    ) {
                        AsyncImage(
                            model = viewModel.generatedImage,
                            contentDescription = "Tap to enlarge",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            generated?.let { bmp ->
                                scope.launch {
                                    val ok = MediaActions.saveBitmapToGallery(context, bmp, "fancyai_${System.currentTimeMillis()}")
                                    android.widget.Toast.makeText(
                                        context,
                                        if (ok) "Saved to gallery" else "Save failed",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save")
                    }
                    OutlinedButton(
                        onClick = { generated?.let { bmp -> scope.launch { MediaActions.shareBitmap(context, bmp, "fancyai_${System.currentTimeMillis()}") } } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.useGeneratedAsSource() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use as source (img2img)")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { keyboardController?.hide(); viewModel.generateImage() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !viewModel.isGenerating && viewModel.prompt.isNotBlank(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Generate Studio Output", fontWeight = FontWeight.Bold)
            }

            if (viewModel.errorMessage != null) {
                Text(
                    viewModel.errorMessage!!,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun StudioParameterMatrix(viewModel: ImagingViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Width", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(value = viewModel.width, onValueChange = { viewModel.updateWidth(it) }, singleLine = true, shape = RoundedCornerShape(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Height", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(value = viewModel.height, onValueChange = { viewModel.updateHeight(it) }, singleLine = true, shape = RoundedCornerShape(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Steps", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(value = viewModel.steps, onValueChange = { viewModel.updateSteps(it) }, singleLine = true, shape = RoundedCornerShape(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("CFG Scale", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(value = viewModel.cfgScale, onValueChange = { viewModel.updateCfgScale(it) }, singleLine = true, shape = RoundedCornerShape(8.dp))
                }
            }
        }
    }
}
