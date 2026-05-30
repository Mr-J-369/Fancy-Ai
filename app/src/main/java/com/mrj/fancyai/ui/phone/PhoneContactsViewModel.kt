package com.mrj.fancyai.ui.phone

import androidx.lifecycle.ViewModel
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.MessengerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** Backs the Phone app's contact list — everyone you can place a voice call to. */
class PhoneContactsViewModel(
    private val messengerRepository: MessengerRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    val contacts: Flow<List<CharacterEntity>> =
        messengerRepository.getInboxItems().map { items -> items.map { it.character } }

    fun resolveAvatar(ref: String?): File? = ref?.let { mediaRepository.resolveToFile(it) }
}
