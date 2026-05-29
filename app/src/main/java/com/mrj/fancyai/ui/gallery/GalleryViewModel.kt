package com.mrj.fancyai.ui.gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.repository.MediaRepository
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    var images: List<File> by mutableStateOf(emptyList())
        private set

    var selectedImage: File? by mutableStateOf(null)
        private set

    init {
        loadImages()
    }

    fun loadImages() {
        viewModelScope.launch {
            images = mediaRepository.getAllImages()
        }
    }

    fun selectImage(image: File) {
        selectedImage = image
    }

    fun deselectImage() {
        selectedImage = null
    }

    fun deleteImage(image: File) {
        viewModelScope.launch {
            val dbRef = "db:${image.name}"
            mediaRepository.deleteImage(dbRef)
            loadImages()
            if (selectedImage == image) {
                selectedImage = null
            }
        }
    }
}
