package com.mrj.fancyai.ui.aura

import android.content.Context
import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val CHROMA_T5_FILE = "t5-v1_1-xxl-encoder-Q4_0.gguf"
internal const val CHROMA_CLIP_FILE = "clip_l.safetensors"
internal const val CHROMA_COMPONENTS_TOTAL_BYTES = 2_679_106_848L + 246_144_152L
private const val AURA_CHROMA_TAG = "AuraChroma"

private val CHROMA_COMPONENT_FILES = listOf(
    DitBaseFile(
        CHROMA_T5_FILE,
        "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/t5-v1_1-xxl-encoder-Q4_0.gguf",
        2_679_106_848L,
    ),
    DitBaseFile(
        CHROMA_CLIP_FILE,
        "https://huggingface.co/comfyanonymous/flux_text_encoders/resolve/main/clip_l.safetensors",
        246_144_152L,
    ),
)

internal fun isChromaComponentsInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "chroma_components")
    return (File(baseDir, CHROMA_T5_FILE).length() > 0L &&
        File(baseDir, CHROMA_CLIP_FILE).length() > 0L)
}

internal fun AuraController.installChromaComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "chroma_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_chroma_components), 0f)
                CHROMA_COMPONENT_FILES.forEach { item ->
                    val target = File(baseDir, item.name)
                    if (!target.isFile || target.length() == 0L) {
                        downloadDirect(app, item.url, target, item.size) { copied, _ ->
                            val currentOverall = completedBytes + copied
                            val total = currentOverall.toFloat() / CHROMA_COMPONENTS_TOTAL_BYTES
                            operation(app.getString(R.string.aura_downloading_chroma_components), total)
                        }
                    }
                    completedBytes += target.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_CHROMA_TAG, "Flux 1 components download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_chroma_components_install_failed))
        }
    }
}
