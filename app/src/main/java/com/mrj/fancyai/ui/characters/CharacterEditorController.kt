package com.mrj.fancyai.ui.characters

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.voice.VoiceLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File

internal class CharacterEditorController(
    private val context: Context,
    private val editorKey: String,
    private val character: CharacterCard?,
) {
    var draft by mutableStateOf(readDraft(context, editorKey, character))
        private set
    var mediaRevision by mutableIntStateOf(0)
    var saving by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    var imageProgress by mutableIntStateOf(0)
        private set
    var generatingAvatar by mutableStateOf(false)
        private set
    var avatarError by mutableIntStateOf(0)
        private set
    var avatarJob: Job? = null
        private set

    fun previewImage(name: String, savedPath: String?): File? =
        if (context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).getBoolean("remove.$name", false)) null else {
            draftImage(context, editorKey, name).takeIf(File::isFile)
                ?: savedPath?.let(::File)?.takeIf(File::isFile)
        }

    fun update(next: CharacterDraft) {
        draft = next
        saveCharacterDraft(context, editorKey, next)
    }

    fun removeImage(name: String) {
        if (saving || choosing || generatingAvatar) return
        draftImage(context, editorKey, name).delete()
        context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).edit { putBoolean("remove.$name", true) }
        mediaRevision++
    }

    suspend fun choose(uri: Uri?, fileName: String, maxEdge: Int) {
        if (uri == null || saving || choosing || generatingAvatar) return
        choosing = true
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                copyResizedDraftImage(context, editorKey, uri, fileName, maxEdge)
            }
            mediaRevision++
        } finally {
            choosing = false
        }
    }

    suspend fun generateAvatar() {
        if (generatingAvatar) {
            avatarJob?.cancel()
            return
        }
        if (saving || choosing) return
        val prompt = draft.appearance.trim()
        if (prompt.isBlank()) {
            avatarError = R.string.character_avatar_appearance_required
            return
        }
        generatingAvatar = true
        avatarError = 0
        avatarJob = currentCoroutineContext()[Job]
        try {
            val image = generatePromptImage(context, prompt, characterId = character?.id ?: draftCharacterId(context, editorKey)) { imageProgress = it }
            withContext(NonCancellable + Dispatchers.IO) {
                copyResizedDraftImage(context, editorKey, Uri.fromFile(image), AVATAR_FILE, AVATAR_MAX_EDGE)
            }
            mediaRevision++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            avatarError = llmErrorResource(failure, R.string.aura_generation_failed)
        } finally {
            generatingAvatar = false
            avatarJob = null
        }
    }

    suspend fun save(): CharacterCard? {
        if (saving || choosing || generatingAvatar) return null
        saving = true
        return try {
            val saved = withContext(Dispatchers.IO) { saveCharacter(context, editorKey, draft, character) }
            VoiceLibrary.moveAssignment(context, editorKey, saved.id)
            saved
        } finally {
            saving = false
        }
    }

    suspend fun resetRoot(): CharacterCard {
        val defaultRoot = withContext(Dispatchers.IO) { resetRootCharacter(context) }
        draft = defaultRoot.toDraft()
        mediaRevision++
        return defaultRoot
    }
}
