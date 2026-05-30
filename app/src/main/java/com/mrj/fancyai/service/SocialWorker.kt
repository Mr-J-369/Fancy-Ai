package com.mrj.fancyai.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Background worker for autonomous character posting.
 */
class SocialWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SocialWorker", "Starting autonomous posting check")
        
        val messengerRepo = ServiceLocator.getMessengerRepository()
        val socialRepo = ServiceLocator.getSocialRepository()
        val autoGenService = ServiceLocator.getAutoGenerationService() // Need to add this to ServiceLocator
        val imageService = ServiceLocator.getImageService()
        val settings = ServiceLocator.getSettingsRepository()

        // 1. Pick a character that has opted into at least one (currently-allowed) platform.
        //    Rebbit additionally requires the global NSFW switch to be on.
        val nsfw = settings.nsfwEnabled
        fun allowedPlatforms(c: com.mrj.fancyai.data.db.entity.CharacterEntity): List<String> = buildList {
            if (c.autoPostUstagram) add("ustagram")
            if (c.autoPostRebbit && nsfw) add("rebbit")
            if (c.autoPostY) add("y")
        }

        val characters = messengerRepo.getInboxItems().first().map { it.character }
            .filter { allowedPlatforms(it).isNotEmpty() }
        if (characters.isEmpty()) return Result.success()

        val character = characters.random()

        // 2. Pick a platform the character actually opted into.
        val platform = allowedPlatforms(character).random()

        try {
            // 3. Generate structured post (the model picks a fitting subreddit + a flux prompt).
            val subredditChoices = if (platform == "rebbit") settings.rebbitSubreddits.toList() else emptyList()
            val content = autoGenService.generatePost(
                charName = character.name,
                persona = character.persona,
                platform = platform,
                subredditChoices = subredditChoices,
                identity = character.bio
            )

            // 4. Generate the attached photo from the flux prompt, if any.
            var imageRef: String? = null
            content.imagePrompt?.let { prompt ->
                val params = mapOf(
                    "width" to settings.imgWidth, "height" to settings.imgHeight,
                    "steps" to settings.imgSteps, "cfg" to settings.imgCfg
                )
                imageService.generate(prompt, params, null).onSuccess { imageRef = it.ref }
            }

            // 5. Save post
            val post = SocialPostEntity(
                id = UUID.randomUUID().toString(),
                charId = character.id,
                platform = platform,
                caption = if (platform == "ustagram") content.text else null,
                imageRef = imageRef,
                title = if (platform == "rebbit") content.title else null,
                subreddit = if (platform == "rebbit") content.subreddit else null,
                text = if (platform != "ustagram") content.text else null,
                timestamp = System.currentTimeMillis()
            )

            socialRepo.insertPost(post)
            Log.d("SocialWorker", "Autonomous post created for ${character.name} on $platform (${content.subreddit ?: ""})")

            return Result.success()
        } catch (e: Exception) {
            Log.e("SocialWorker", "Failed to generate autonomous post", e)
            return Result.retry()
        }
    }
}
