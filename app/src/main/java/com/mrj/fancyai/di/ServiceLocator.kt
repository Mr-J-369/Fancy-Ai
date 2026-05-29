package com.mrj.fancyai.di

import android.content.Context
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.repository.CharacterRepository
import com.mrj.fancyai.data.repository.ChatRepository
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.data.repository.SocialRepository
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.ui.characters.CharacterViewModel
import com.mrj.fancyai.ui.chat.ChatViewModel
import com.mrj.fancyai.ui.gallery.GalleryViewModel
import com.mrj.fancyai.ui.imaging.ImagingViewModel
import com.mrj.fancyai.ui.settings.SettingsViewModel
import com.mrj.fancyai.ui.social.SocialViewModel

object ServiceLocator {
    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var characterRepository: CharacterRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var socialRepository: SocialRepository
    private lateinit var mediaRepository: MediaRepository
    private lateinit var llamaEngine: LlamaEngine

    fun initialize(context: Context) {
        database = AppDatabase.getInstance(context)
        settingsRepository = SettingsRepository(context)
        characterRepository = CharacterRepository(database)
        chatRepository = ChatRepository(database)
        socialRepository = SocialRepository(database)
        mediaRepository = MediaRepository(context)
        llamaEngine = LlamaEngine()
    }

    fun getCharacterViewModel(): CharacterViewModel =
        CharacterViewModel(characterRepository)

    fun getChatViewModel(charId: String): ChatViewModel =
        ChatViewModel(charId, chatRepository, settingsRepository, llamaEngine)

    fun getSettingsViewModel(): SettingsViewModel =
        SettingsViewModel(settingsRepository)

    fun getSocialViewModel(platform: String): SocialViewModel =
        SocialViewModel(platform, socialRepository, chatRepository, llamaEngine)

    fun getGalleryViewModel(): GalleryViewModel =
        GalleryViewModel(mediaRepository)

    fun getImagingViewModel(): ImagingViewModel =
        ImagingViewModel(settingsRepository, mediaRepository)

    fun getLlamaEngine(): LlamaEngine = llamaEngine

    fun getCharacterRepository(): CharacterRepository = characterRepository

    fun getChatRepository(): ChatRepository = chatRepository

    fun getSocialRepository(): SocialRepository = socialRepository

    fun getMediaRepository(): MediaRepository = mediaRepository

    fun getSettingsRepository(): SettingsRepository = settingsRepository
}
