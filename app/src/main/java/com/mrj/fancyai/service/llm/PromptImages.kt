package com.mrj.fancyai.service.llm

import android.content.Context
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.ui.aura.AuraImages
import com.mrj.fancyai.ui.aura.auraGenerationConfig
import com.mrj.fancyai.ui.aura.withSeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Render the supplied description directly through Aura. */
internal suspend fun generatePromptImage(
    context: Context, description: String, freshSeed: Boolean = false, characterId: String?,
    archive: Boolean = true,
    onProgress: (Int) -> Unit = {},
): File {
    return try {
        withContext(Dispatchers.Main.immediate) { onProgress(0) }
        val config = withContext(Dispatchers.IO) {
            auraGenerationConfig(context, description)
        }
        val settings = if (freshSeed) {
            val seed = SeedPolicy.roll()
            config.withSeed(seed)
        } else config
        coroutineScope {
            AuraImages.execute(context, settings = settings, characterId = characterId, archive = archive,
                onProgress = { percent -> launch(Dispatchers.Main.immediate) { onProgress(percent) } })
        }
    } catch (failure: MacroException) {
        com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Aura", "Image generation failed: ${failure.message}", failure)
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        com.mrj.fancyai.util.AppLog.write(android.util.Log.ERROR, "Aura", "Image generation failed: ${failure.message}", failure)
        throw MacroException(R.string.aura_generation_failed, "Image generation failed").apply { initCause(failure) }
    }
}

internal fun readPromptImage(image: File): String {
    val metadata = File(image.parentFile, "${image.nameWithoutExtension}.json")
    if (!metadata.isFile) return ""
    return (Json.parseToJsonElement(metadata.readText()).jsonObject["prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

