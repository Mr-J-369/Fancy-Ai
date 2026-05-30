package com.mrj.fancyai.di

import android.content.Context
import com.mrj.fancyai.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.mrj.fancyai.data.repository.CharacterRepository
import com.mrj.fancyai.data.repository.ChatRepository
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.MessengerRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.data.repository.StateRepository
import com.mrj.fancyai.data.repository.SocialRepository
import com.mrj.fancyai.domain.inference.LlamaEngine
import com.mrj.fancyai.domain.inference.ModelLoader
import com.mrj.fancyai.ui.chat.MessengerViewModel
import com.mrj.fancyai.ui.games.GamesViewModel
import com.mrj.fancyai.ui.gallery.GalleryViewModel
import com.mrj.fancyai.ui.imaging.ImagingViewModel
import com.mrj.fancyai.ui.phone.PhoneViewModel
import com.mrj.fancyai.ui.phone.PhoneContactsViewModel
import com.mrj.fancyai.ui.settings.SettingsViewModel
import com.mrj.fancyai.ui.social.SocialViewModel
import com.mrj.fancyai.service.AgentService
import com.mrj.fancyai.service.AutoGenerationService
import com.mrj.fancyai.service.CloudLlmService
import com.mrj.fancyai.service.ImageService
import com.mrj.fancyai.service.VisionService
import com.mrj.fancyai.service.VoiceService

object ServiceLocator {
    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var stateRepository: StateRepository
    private lateinit var characterRepository: CharacterRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var messengerRepository: MessengerRepository
    private lateinit var socialRepository: SocialRepository
    private lateinit var mediaRepository: MediaRepository
    private lateinit var llamaEngine: LlamaEngine
    private lateinit var imageService: ImageService
    private lateinit var cloudLlmService: CloudLlmService
    private lateinit var voiceService: VoiceService
    private lateinit var visionService: VisionService
    private lateinit var agentService: AgentService
    private lateinit var autoGenerationService: AutoGenerationService
    private lateinit var modelLoader: ModelLoader
    private lateinit var backupService: com.mrj.fancyai.service.BackupService

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        database = AppDatabase.getInstance(appContext)

        settingsRepository = SettingsRepository(appContext)
        stateRepository = StateRepository(appContext, database, settingsRepository)
        
        // Seed and migrate data on launch
        appScope.launch { stateRepository.initializeState() }

        characterRepository = CharacterRepository(database)
        chatRepository = ChatRepository(database)
        messengerRepository = MessengerRepository(database)
        socialRepository = SocialRepository(database)
        mediaRepository = MediaRepository(appContext)

        // Initialize and register core services in the Plug-and-Play Registry
        llamaEngine = LlamaEngine()
        imageService = ImageService(settingsRepository, mediaRepository)
        cloudLlmService = CloudLlmService()
        voiceService = VoiceService(context)
        visionService = VisionService(context)
        agentService = AgentService()
        autoGenerationService = AutoGenerationService(llamaEngine, settingsRepository, cloudLlmService)
        modelLoader = ModelLoader(appContext, settingsRepository)
        backupService = com.mrj.fancyai.service.BackupService(database, mediaRepository, settingsRepository, appContext)
        // NOTE: the local model is loaded lazily when a chat is opened (see
        // MessengerViewModel.loadConversation), not on app launch — it's a multi-GB
        // load and most app surfaces (social, gallery, cloud chats) don't need it.

        ServiceRegistry.register(LlamaEngine::class.java, llamaEngine)
        ServiceRegistry.register(ImageService::class.java, imageService)
        ServiceRegistry.register(VoiceService::class.java, voiceService)
        ServiceRegistry.register(VisionService::class.java, visionService)
        ServiceRegistry.register(AgentService::class.java, agentService)
    }

    fun getMessengerViewModel(): MessengerViewModel =
        MessengerViewModel(messengerRepository, mediaRepository, settingsRepository, llamaEngine, autoGenerationService, modelLoader, cloudLlmService, imageService)

    fun getSettingsViewModel(): SettingsViewModel =
        SettingsViewModel(settingsRepository, modelLoader, cloudLlmService, backupService)

    fun getModelLoader(): ModelLoader = modelLoader

    fun getSocialViewModel(platform: String): SocialViewModel =
        SocialViewModel(platform, socialRepository, autoGenerationService, imageService, mediaRepository, settingsRepository)

    fun getGalleryViewModel(): GalleryViewModel =
        GalleryViewModel(mediaRepository, database)

    fun getImagingViewModel(): ImagingViewModel =
        ImagingViewModel(imageService, settingsRepository)

    fun getGamesViewModel(): GamesViewModel =
        GamesViewModel(messengerRepository, mediaRepository, settingsRepository, llamaEngine, cloudLlmService, imageService)

    fun getPhoneViewModel(charId: String): PhoneViewModel =
        PhoneViewModel(charId, messengerRepository, mediaRepository, settingsRepository, llamaEngine, voiceService)

    fun getPhoneContactsViewModel(): PhoneContactsViewModel =
        PhoneContactsViewModel(messengerRepository, mediaRepository)

    fun getLlamaEngine(): LlamaEngine = llamaEngine

    fun getVoiceService(): VoiceService = voiceService

    fun getVisionService(): VisionService = visionService

    fun getAgentService(): AgentService = agentService

    fun getAutoGenerationService(): AutoGenerationService = autoGenerationService

    fun getImageService(): ImageService = imageService

    fun getCharacterRepository(): CharacterRepository = characterRepository

    fun getChatRepository(): ChatRepository = chatRepository

    fun getMessengerRepository(): MessengerRepository = messengerRepository

    fun getSocialRepository(): SocialRepository = socialRepository

    fun getMediaRepository(): MediaRepository = mediaRepository

    fun getSettingsRepository(): SettingsRepository = settingsRepository
}
