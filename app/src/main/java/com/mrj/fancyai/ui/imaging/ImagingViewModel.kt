package com.mrj.fancyai.ui.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.service.ImageService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream

class ImagingViewModel(
    private val imageService: ImageService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Prompt & Analysis
    var prompt by mutableStateOf("")
        private set
    var tokenCount by mutableStateOf(0)
        private set
    private var tokenJob: Job? = null

    // Per-generation parameters (persisted to settings so they survive navigation).
    // Width, height, steps and CFG are edited here in the Imaging Studio; the backend
    // URLs and the Local Dream scheduler are configured in Settings.
    var width by mutableStateOf(settingsRepository.imgWidth.toString())
        private set
    var height by mutableStateOf(settingsRepository.imgHeight.toString())
        private set
    var steps by mutableStateOf(settingsRepository.imgSteps.toString())
        private set
    var cfgScale by mutableStateOf(settingsRepository.imgCfg.toString())
        private set
    var denoisingStrength by mutableStateOf(settingsRepository.imgDenoising)
        private set
    var useLocalDream by mutableStateOf(settingsRepository.useLocalDream)
        private set

    fun updateWidth(value: String) {
        width = value
        value.toIntOrNull()?.let { settingsRepository.imgWidth = it }
    }

    fun updateHeight(value: String) {
        height = value
        value.toIntOrNull()?.let { settingsRepository.imgHeight = it }
    }

    fun updateSteps(value: String) {
        steps = value
        value.toIntOrNull()?.let { settingsRepository.imgSteps = it }
    }

    fun updateCfgScale(value: String) {
        cfgScale = value
        value.toFloatOrNull()?.let { settingsRepository.imgCfg = it }
    }

    fun updateDenoising(value: Float) {
        denoisingStrength = value
        settingsRepository.imgDenoising = value
    }

    fun updateUseLocalDream(value: Boolean) {
        useLocalDream = value
        settingsRepository.useLocalDream = value
    }

    // State
    var isGenerating by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0)
        private set
    var sourceImage by mutableStateOf<Bitmap?>(null)
        private set
    var sourceImageB64 by mutableStateOf<String?>(null)
        private set
    var generatedImage by mutableStateOf<Bitmap?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun updatePrompt(text: String) {
        prompt = text
        tokenJob?.cancel()
        tokenJob = viewModelScope.launch {
            delay(500)
            tokenCount = imageService.getTokenCount(text)
        }
    }

    fun setSourceImage(inputStream: InputStream?) {
        viewModelScope.launch {
            inputStream?.use { 
                val bitmap = BitmapFactory.decodeStream(it)
                sourceImage = bitmap
                
                // Convert to Base64 for API. NO_WRAP — the default inserts newlines
                // every 76 chars, which the image servers reject (HTTP 400).
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                sourceImageB64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
    }

    fun clearSourceImage() {
        sourceImage = null
        sourceImageB64 = null
    }

    /** Feeds the just-generated image back in as the img2img source for a follow-up pass. */
    fun useGeneratedAsSource() {
        val generated = generatedImage ?: return
        sourceImage = generated
        val out = java.io.ByteArrayOutputStream()
        generated.compress(Bitmap.CompressFormat.PNG, 100, out)
        sourceImageB64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        generatedImage = null
    }

    fun generateImage() {
        if (prompt.isBlank() || isGenerating) return

        viewModelScope.launch {
            isGenerating = true
            progress = 0
            errorMessage = null
            generatedImage = null

            val params = mapOf(
                "steps" to (steps.toIntOrNull() ?: 20),
                "cfg" to (cfgScale.toFloatOrNull() ?: 7.0f),
                "width" to (width.toIntOrNull() ?: 512),
                "height" to (height.toIntOrNull() ?: 512),
                "denoising" to denoisingStrength
            )

            val result = imageService.generate(prompt, params, sourceImageB64) { pct ->
                progress = pct
            }

            result.onSuccess {
                generatedImage = it.bitmap
            }.onFailure {
                errorMessage = it.message ?: "Studio generation failed"
            }

            isGenerating = false
        }
    }

    fun clearAll() {
        generatedImage = null
        sourceImage = null
        sourceImageB64 = null
        prompt = ""
        errorMessage = null
        progress = 0
    }
}
