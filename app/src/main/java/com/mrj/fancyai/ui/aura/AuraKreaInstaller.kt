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

internal const val KREA2_COMPONENTS_TOTAL_BYTES = 2_636_621_833L
private const val AURA_KREA2_TAG = "AuraKrea2"

private val KREA2_COMPONENT_FILES = listOf(
    DitBaseFile(
        "Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf",
        "https://huggingface.co/bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf",
        2_375_774_112L,
    ),
    DitBaseFile(
        "wan_2.1_vae.safetensors",
        "https://huggingface.co/Osrivers/wan_2.1_vae.safetensors/resolve/main/wan_2.1_vae.safetensors",
        253_815_318L,
    ),
    DitBaseFile(
        "tokenizer.json",
        "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json",
        7_032_403L,
    ),
)

internal fun isKreaComponentsInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "krea_components")
    return (File(baseDir, "Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf").length() > 0L &&
        File(baseDir, "wan_2.1_vae.safetensors").length() > 0L)
}

internal fun AuraController.installKreaComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "krea_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_krea_components), 0f)
                KREA2_COMPONENT_FILES.forEach { item ->
                    val target = File(baseDir, item.name)
                    if (!target.isFile || target.length() == 0L) {
                        downloadDirect(app, item.url, target, item.size) { copied, _ ->
                            val currentOverall = completedBytes + copied
                            val total = currentOverall.toFloat() / KREA2_COMPONENTS_TOTAL_BYTES
                            operation(app.getString(R.string.aura_downloading_krea_components), total)
                        }
                    }
                    completedBytes += target.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_KREA2_TAG, "Krea components download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_krea_components_install_failed))
        }
    }
}
