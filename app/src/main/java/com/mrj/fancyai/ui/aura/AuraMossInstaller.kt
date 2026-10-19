package com.mrj.fancyai.ui.aura

import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val MOSS_BYTES = 3_327_400_800L
internal const val MOSS_LLM_BYTES = 5_379_417_312L
internal const val MOSS_TOKENIZER_BYTES = 12_807_982L
private const val MOSS_URL =
    "https://huggingface.co/leejet/MOSS-Image-Base-GGUF/resolve/main/MOSS-Image-Base-Q4_0.gguf"
private const val MOSS_LLM_URL =
    "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_0.gguf"
private const val MOSS_TOKENIZER_URL =
    "https://huggingface.co/Qwen/Qwen3.5-9B/resolve/main/tokenizer.json"
private const val AURA_MOSS_TAG = "AuraMoss"
private const val MOSS_MODEL_ID = "moss_image"

internal fun AuraController.installMoss() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val modelDir = File(File(app.filesDir, "sd_models"), MOSS_MODEL_ID)
        val staging = File(File(app.filesDir, "sd_models"), ".$MOSS_MODEL_ID.downloading").apply { mkdirs() }
        val total = MOSS_BYTES + MOSS_LLM_BYTES + MOSS_TOKENIZER_BYTES
        var completed = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                File(staging, "MOSS").createNewFile()
                operation(app.getString(R.string.aura_downloading_moss), 0f)
                downloadResuming(app, AURA_MOSS_TAG, MOSS_LLM_URL, File(staging, "Qwen3.5-9B-Q4_0.gguf"), MOSS_LLM_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_moss), (completed + copied).toFloat() / total)
                }
                completed += File(staging, "Qwen3.5-9B-Q4_0.gguf").length()
                downloadResuming(app, AURA_MOSS_TAG, MOSS_TOKENIZER_URL, File(staging, "tokenizer.json"), MOSS_TOKENIZER_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_moss), (completed + copied).toFloat() / total)
                }
                completed += File(staging, "tokenizer.json").length()
                downloadResuming(app, AURA_MOSS_TAG, MOSS_URL, File(staging, "MOSS-Image-Base-Q4_0.gguf"), MOSS_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_moss), (completed + copied).toFloat() / total)
                }
                SdModel.validateImported(staging)
                modelDir.deleteRecursively()
                check(staging.renameTo(modelDir)) { "Could not finalize model installation" }
            }
            true
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_MOSS_TAG, "MOSS-Image download failed", failure)
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh(modelDir.path)
        } else {
            refresh(null)
            state = state.copy(error = app.getString(R.string.aura_moss_install_failed))
        }
    }
}
