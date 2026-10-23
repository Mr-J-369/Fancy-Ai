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

internal const val MOSS_COMPONENTS_TOTAL_BYTES = 5_379_417_312L + 12_807_982L + 335_544_320L
private const val AURA_MOSS_TAG = "AuraMoss"

private val MOSS_COMPONENT_FILES = listOf(
    DitBaseFile(
        "Qwen3.5-9B-Q4_0.gguf",
        "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_0.gguf",
        5_379_417_312L,
    ),
    DitBaseFile(
        "tokenizer.json",
        "https://huggingface.co/Qwen/Qwen3.5-9B/resolve/main/tokenizer.json",
        12_807_982L,
    ),
    DitBaseFile(
        "flux2-vae.safetensors",
        "https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/vae/flux2-vae.safetensors",
        335_544_320L,
    ),
)

internal fun isMossComponentsInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "moss_components")
    return (File(baseDir, "Qwen3.5-9B-Q4_0.gguf").length() > 0L &&
        File(baseDir, "tokenizer.json").length() > 0L &&
        File(baseDir, "flux2-vae.safetensors").length() > 0L)
}

internal fun AuraController.installMossComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "moss_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_moss_components), 0f)
                MOSS_COMPONENT_FILES.forEach { item ->
                    val target = File(baseDir, item.name)
                    if (!target.isFile || target.length() == 0L) {
                        downloadDirect(app, item.url, target, item.size) { copied, _ ->
                            val currentOverall = completedBytes + copied
                            val total = currentOverall.toFloat() / MOSS_COMPONENTS_TOTAL_BYTES
                            operation(app.getString(R.string.aura_downloading_moss_components), total)
                        }
                    }
                    completedBytes += target.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_MOSS_TAG, "MOSS components download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_moss_components_install_failed))
        }
    }
}
