package com.mrj.fancyai.ui.music

import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.LlmRuntime
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.ui.music.RootProducerController.Companion.KEY_BRIEF
import com.mrj.fancyai.ui.music.RootProducerController.Companion.KEY_LYRICS
import com.mrj.fancyai.ui.music.RootProducerController.Companion.KEY_PLAN_READY
import com.mrj.fancyai.ui.music.RootProducerController.Companion.KEY_TITLE
import com.mrj.fancyai.ui.music.RootProducerController.Companion.PRODUCER_PLAN_OUTPUT_TOKENS
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.ProAccess
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun RootProducerController.writePlan() {
    if (drafting) {
        stopDraft()
        return
    }
    val request = idea.trim()
    if (request.isEmpty()) return
    val run = draftRun + 1
    draftRun = run
    thoughtProcess = ""
    imagePath = null
    preferences.edit { remove("thought_process") }
    drafting = true
    notice = null
    draftJob = scope.launch {
        var opened: LlmEngineClient? = null
        try {
            val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) }
            if (settings == null) {
                notice = app.getString(R.string.producer_engine_required)
                return@launch
            }
            val turn = AssistantProtocol.compile(
                settings.runtime,
                macros = MacroBus(app, character = null, profile = null),
                message = request,
                instructions = listOf(
                    "You are a music producer. Create a complete music production plan from the user's idea.",
                    "Output plain-text fields only in this exact format with NO preface, greeting, introductory remarks, commentary, thinking, or markdown code fences:\n" +
                        "title: Song title only\n" +
                        "brief: Detailed production brief describing genre, mood, tempo/BPM, instruments, and arrangement style\n" +
                        "lyrics: Full song lyrics with section labels such as [Verse 1], [Chorus], [Outro]; leave empty if instrumental\n\n" +
                        "End with a short description of the album cover artwork in this exact format:\n\n" +
                        "<scene_prompt>[visual subject], [art style], [mood], [colors], [lighting]</scene_prompt>",
                ),
                includeIdentity = false,
            )
            val startup = withContext(Dispatchers.IO) {
                val config = settings.sessionConfig(turn.systemInstruction)
                val outputBudget = maxOf(config.maxOutputTokens, PRODUCER_PLAN_OUTPUT_TOKENS)
                config.copy(
                    maxOutputTokens = outputBudget,
                    cloudGenerationParameters = config.cloudGenerationParameters.mapValues { (name, value) ->
                        if ((name == "max_tokens") || (name == "max_completion_tokens")) {
                            maxOf(value.toIntOrNull() ?: 0, outputBudget).toString()
                        } else value
                    },
                )
            }
            val active = LlmEngineClient(app)
            opened = active
            llmRuntime.set(active)
            val response = active.generate(LlmRequest(
                config = startup,
                input = turn.input,
                thinking = false,
            )).complete()
            if (run == draftRun) {
                thoughtProcess = response.channels.values.joinToString("\n\n")
                preferences.edit { putString("thought_process", thoughtProcess) }
                val (raw, scene) = ImagePrompt.split(response.text, imageOnly = turn.imageRequested)
                if (!turn.imageRequested) {
                    title = SocialPostJson.field(raw, "title").orEmpty().lineSequence().firstOrNull(String::isNotBlank)?.trim().orEmpty()
                    brief = SocialPostJson.text(raw, "brief")
                    lyrics = SocialPostJson.field(raw, "lyrics").orEmpty().trim()
                    planReady = true
                    preferences.edit {
                        putString(KEY_TITLE, title)
                        putString(KEY_BRIEF, brief)
                        putString(KEY_LYRICS, lyrics)
                        putBoolean(KEY_PLAN_READY, true)
                    }
                }
                scene?.let { imagePath = generatePromptImage(app, it, characterId = null).path }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (run == draftRun) notice = app.getString(llmErrorResource(failure, R.string.chat_engine_failed))
        } finally {
            if (llmRuntime.compareAndSet(opened, null)) opened?.close()
            if (run == draftRun) {
                drafting = false
                draftJob = null
            }
        }
    }
}

internal fun RootProducerController.generate() {
    if (generating) return
    val songTitle = title.trim()
    val productionBrief = brief.trim()
    val apiKey = openRouterKey
    val songLyrics = lyrics.trim()
    if (songTitle.isEmpty() || productionBrief.isEmpty() || apiKey.isBlank()) return
    val selectedTier = tier
    val run = musicRun + 1
    musicRun = run
    generating = true
    notice = null
    musicJob = scope.launch {
        try {
            val turn = AssistantProtocol.compile(
                LlmRuntime.CLOUD,
                macros = MacroBus(app, character = null, profile = null),
                message = "Create a song from the production brief.",
                instructions = listOf("An MP3 song. Supplied lyrics retain their language and section structure."),
                context = buildList {
                    add("Production brief:\n$productionBrief")
                    if (songLyrics.isBlank()) {
                        add("Vocal mode: instrumental, without vocals or spoken words.")
                    } else {
                        add("Vocal mode: supplied lyrics.\nLyrics:\n$songLyrics")
                    }
                },
                includeIdentity = false,
            )
            val track = ProAccess.songsLock.withLock {
                val payload = musicRuntime.generate(apiKey, selectedTier, turn)
                withContext(NonCancellable + Dispatchers.IO) {
                    RootMusicStore.save(app, songTitle, productionBrief, songLyrics, selectedTier, payload)
                }
            }
            if (run == musicRun) {
                tracks = listOf(track) + tracks.filterNot { it.id == track.id }
                notice = app.getString(R.string.producer_track_ready)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (run == musicRun) notice = app.getString(llmErrorResource(failure, R.string.chat_engine_failed))
        } finally {
            if (run == musicRun) {
                generating = false
                musicJob = null
            }
        }
    }
}
