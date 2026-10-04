package com.mrj.fancyai.ui.profile

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.ui.characters.copyResizedImage
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class UserProfile(
    val name: String = "",
    val handle: String = "",
    val description: String = "",
    val appearance: String = "",
    val avatarPath: String? = null,
)

internal fun userProfile(context: Context): UserProfile {
    val values = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
    return UserProfile(
        name = values.getString("name", "").orEmpty(),
        handle = values.getString("handle", "").orEmpty(),
        description = values.getString("description", "").orEmpty(),
        appearance = values.getString("appearance", "").orEmpty(),
        avatarPath = File(context.filesDir, "user/avatar.webp").takeIf(File::isFile)?.absolutePath,
    )
}

internal class UserProfileController(private val context: Context) {
    private val preferences = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
    private val drafts = context.getSharedPreferences("user_profile_draft", Context.MODE_PRIVATE)
    private val draftRoot = File(context.filesDir, "user_draft")
    private val draftAvatar = File(draftRoot, "avatar.webp")
    private val savedAvatar = File(context.filesDir, "user/avatar.webp")
    var draft by mutableStateOf(readDraft())
        private set
    var mediaRevision by mutableIntStateOf(0)
        private set
    val avatar: File?
        get() = if (drafts.getBoolean("remove.avatar", false)) null
        else draftAvatar.takeIf(File::isFile) ?: savedAvatar.takeIf(File::isFile)

    fun readDraft(): UserProfile {
        if (!drafts.getBoolean("started", false)) return userProfile(context)
        return UserProfile(
            name = drafts.getString("name", "").orEmpty(),
            handle = drafts.getString("handle", "").orEmpty(),
            description = drafts.getString("description", "").orEmpty(),
            appearance = drafts.getString("appearance", "").orEmpty(),
        )
    }

    fun saveDraft(profile: UserProfile) {
        drafts.edit {
            putBoolean("started", true)
            putString("name", profile.name)
            putString("handle", profile.handle)
            putString("description", profile.description)
            putString("appearance", profile.appearance)
        }
    }

    fun updateDraft(next: UserProfile) {
        draft = next
        saveDraft(next)
    }

    suspend fun importAvatar(uri: Uri) {
        withContext(Dispatchers.IO) { copyResizedImage(context, uri, draftAvatar, 1024) }
        drafts.edit {
            putBoolean("remove.avatar", false)
        }
        mediaRevision++
    }

    fun removeAvatar() {
        draftAvatar.delete()
        drafts.edit {
            putBoolean("remove.avatar", true)
        }
        mediaRevision++
    }

    suspend fun save() {
        val profile = draft
        withContext(Dispatchers.IO) {
            if (drafts.getBoolean("remove.avatar", false)) {
                savedAvatar.delete()
            } else if (draftAvatar.isFile) {
                savedAvatar.parentFile!!.mkdirs()
                draftAvatar.copyTo(savedAvatar, overwrite = true)
            }
            preferences.edit(commit = true) {
                putString("name", profile.name.trim().capitalizeFirstVisibleLetter())
                putString("handle", profile.handle.trim())
                putString("description", profile.description.trim().capitalizeFirstVisibleLetter())
                putString("appearance", profile.appearance.trim().capitalizeFirstVisibleLetter())
            }
            draftRoot.deleteRecursively()
            drafts.edit(commit = true) { clear() }
        }
        draft = readDraft()
        mediaRevision++
    }
}
