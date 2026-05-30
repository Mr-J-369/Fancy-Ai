package com.mrj.fancyai.ui.gallery

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.repository.MediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(
    private val mediaRepository: MediaRepository,
    private val db: AppDatabase
) : ViewModel() {

    var images by mutableStateOf<List<File>>(emptyList())
        private set

    var folders by mutableStateOf<Map<String, List<File>>>(emptyMap())
        private set

    var selectedFolder by mutableStateOf<String?>(null)
        private set

    var selectedImage by mutableStateOf<File?>(null)
        private set

    /** Bucket for images not tied to any character (e.g. Imaging Studio output). */
    private val generatedFolder = "Generated"

    init {
        loadImages()
    }

    private fun loadImages() {
        viewModelScope.launch {
            val allImages = mediaRepository.getAllImages()
            images = allImages

            // Build a filename → character-name map from every place an image is attached to
            // a character: avatars, chat photos, and social-post images. Anything left over is
            // Imaging Studio output and goes to the "Generated" folder.
            val characters = db.characterDao().getAll().first()
            val nameById = characters.associate { it.id to it.name }
            val fileToChar = HashMap<String, String>()

            fun fileNameOf(ref: String?): String? =
                ref?.takeIf { it.startsWith("db:") }?.removePrefix("db:")

            characters.forEach { c ->
                fileNameOf(c.avatarRef)?.let { fileToChar[it] = c.name }
            }
            db.messageDao().getAllMessages().first()
                .filter { it.type == "image" }
                .forEach { msg ->
                    val name = nameById[msg.charId] ?: return@forEach
                    fileNameOf(msg.text)?.let { fileToChar[it] = name }
                }
            db.socialPostDao().getAllOnce().forEach { post ->
                val name = nameById[post.charId] ?: return@forEach
                fileNameOf(post.imageRef)?.let { fileToChar[it] = name }
            }

            folders = allImages
                .groupBy { file -> fileToChar[file.name] ?: generatedFolder }
                // Show character folders first (alphabetical), with "Generated" pinned last.
                .toSortedMap(compareBy({ it == generatedFolder }, { it }))
        }
    }

    fun selectFolder(name: String?) {
        selectedFolder = name
    }

    fun selectImage(file: File?) {
        selectedImage = file
    }

    fun deselectImage() {
        selectedImage = null
    }

    fun deleteImage(file: File) {
        viewModelScope.launch {
            file.delete()
            loadImages()
            if (selectedImage == file) {
                selectedImage = null
            }
        }
    }
}
