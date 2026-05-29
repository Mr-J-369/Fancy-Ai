package com.mrj.fancyai.ui.imaging

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import android.util.Base64

@Composable
fun ImagingScreen(
    navController: NavHostController,
    viewModel: ImagingViewModel
) {
    val prompt = viewModel.prompt
    val isGenerating = viewModel.isGenerating
    val generatedImage = viewModel.generatedImageBase64
    val error = viewModel.errorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Image Generation (Forge/A1111)",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = { viewModel.updatePrompt(it) },
            label = { Text("Describe the image...") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxLines = 6
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.generateImage() },
            enabled = !isGenerating && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isGenerating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating...")
                }
            } else {
                Text("Generate Image")
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp)
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (generatedImage != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Generated Image",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val decodedBitmap = remember(generatedImage) {
                    try {
                        val bytes = Base64.decode(generatedImage, Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (decodedBitmap != null) {
                    AsyncImage(
                        model = decodedBitmap,
                        contentDescription = "Generated image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Failed to decode image")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.saveGeneratedImage() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("💾 Save to Gallery")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
