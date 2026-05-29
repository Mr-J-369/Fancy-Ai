package com.mrj.fancyai.di

import android.content.Context
import com.mrj.fancyai.data.db.AppDatabase
import com.mrj.fancyai.data.repository.CharacterRepository
import com.mrj.fancyai.data.repository.ChatRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.ui.characters.CharacterViewModel
import com.mrj.fancyai.ui.chat.ChatViewModel
import com.mrj.fancyai.ui.settings.SettingsViewModel

object ServiceLocator {
    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var characterRepository: CharacterRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var llamaEngine: LlamaEngine

    fun initialize(context: Context) {
        database = AppDatabase.getInstance(context)
        settingsRepository = SettingsRepository(context)
        characterRepository = CharacterRepository(database)
        chatRepository = ChatRepository(database)
        llamaEngine = LlamaEngine()
    }

    fun getCharacterViewModel(): CharacterViewModel =
        CharacterViewModel(characterRepository)

    fun getChatViewModel(charId: String): ChatViewModel =
        ChatViewModel(charId, chatRepository, settingsRepository, llamaEngine)

    fun getSettingsViewModel(): SettingsViewModel =
        SettingsViewModel(settingsRepository)

    fun getLlamaEngine(): LlamaEngine = llamaEngine

    fun getCharacterRepository(): CharacterRepository = characterRepository

    fun getChatRepository(): ChatRepository = chatRepository

    fun getSettingsRepository(): SettingsRepository = settingsRepository
}
