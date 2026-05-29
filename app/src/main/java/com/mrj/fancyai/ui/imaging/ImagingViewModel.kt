package com.mrj.fancyai.ui.imaging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface ImageGenApi {
    @POST("/sdapi/v1/txt2img")
    suspend fun generateImage(@Body request: ImageGenRequest): ImageGenResponse

    data class ImageGenRequest(
        val prompt: String,
        val negative_prompt: String = "",
        val steps: Int = 20,
        val sampler_name: String = "DPM++ 2M Karras"
    )

    data class ImageGenResponse(
        val images: List<String>
    )
}

class ImagingViewModel(
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    var prompt by mutableStateOf("")
        private set

    var isGenerating by mutableStateOf(false)
        private set

    var generatedImageBase64 by mutableStateOf<String?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun updatePrompt(text: String) {
        prompt = text
    }

    fun generateImage() {
        if (prompt.isBlank()) return

        viewModelScope.launch {
            isGenerating = true
            errorMessage = null
            generatedImageBase64 = null

            try {
                val backendUrl = settingsRepository.customBackendUrl.ifBlank {
                    "http://127.0.0.1:7860"
                }

                val retrofit = Retrofit.Builder()
                    .baseUrl(backendUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(ImageGenApi::class.java)
                val request = ImageGenApi.ImageGenRequest(prompt = prompt)
                val response = api.generateImage(request)

                if (response.images.isNotEmpty()) {
                    generatedImageBase64 = response.images[0]
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Image generation failed"
            } finally {
                isGenerating = false
            }
        }
    }

    fun saveGeneratedImage() {
        val base64 = generatedImageBase64 ?: return

        viewModelScope.launch {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                mediaRepository.saveImage(bitmap)
                generatedImageBase64 = null
                prompt = ""
            } catch (e: Exception) {
                errorMessage = "Failed to save image: ${e.message}"
            }
        }
    }
}
