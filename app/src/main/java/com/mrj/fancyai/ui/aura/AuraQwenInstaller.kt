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

internal const val QWEN_COMPONENTS_TOTAL_BYTES = 5_462_843_288L
internal const val QWEN_IMAGE_2_1_BYTES = 4_197_494_816L
private const val QWEN_IMAGE_2_1_URL =
    "https://huggingface.co/leejet/Qwen-Image-2.1-GGUF/resolve/main/qwen_image_2.1-Q4_0.gguf"
private const val AURA_QWEN_TAG = "AuraQwen"

private val QWEN_COMPONENT_FILES = listOf(
    DitBaseFile(
        "Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
        "https://huggingface.co/bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf",
        4_787_333_600L,
    ),
    DitBaseFile(
        "qwen_image_2.1_vae_bf16.safetensors",
        "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/main/vae/qwen_image_2.1_vae_bf16.safetensors",
        675_509_688L,
    ),
)

internal fun isQwenComponentsInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "qwen_components")
    return (File(baseDir, "Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf").length() > 0L &&
        File(baseDir, "qwen_image_2.1_vae_bf16.safetensors").length() > 0L)
}

internal fun AuraController.installQwenComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "qwen_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_qwen_components), 0f)
                QWEN_COMPONENT_FILES.forEach { item ->
                    val target = File(baseDir, item.name)
                    if (!target.isFile || target.length() == 0L) {
                        downloadDirect(item.url, target, item.size) { copied, _ ->
                            val currentOverall = completedBytes + copied
                            val total = currentOverall.toFloat() / QWEN_COMPONENTS_TOTAL_BYTES
                            operation(app.getString(R.string.aura_downloading_qwen_components), total)
                        }
                    }
                    completedBytes += target.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_QWEN_TAG, "Qwen components download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_qwen_components_install_failed))
        }
    }
}

internal fun AuraController.installQwenImage21() = installDirectModel(
    modelId = "qwen_image_2_1",
    marker = "QWEN",
    url = QWEN_IMAGE_2_1_URL,
    fileName = "qwen_image_2.1-Q4_0.gguf",
    expectedBytes = QWEN_IMAGE_2_1_BYTES,
    downloadingRes = R.string.aura_downloading_qwen_image_2_1,
    failedRes = R.string.aura_qwen_image_2_1_install_failed,
    logTag = "Qwen Image 2.1",
)
