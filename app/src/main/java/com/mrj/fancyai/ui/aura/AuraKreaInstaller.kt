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

internal const val KREA2_BYTES = 6_837_146_720L
internal const val KREA2_LLM_BYTES = 2_375_774_112L
internal const val KREA2_VAE_BYTES = 253_815_318L
internal const val KREA2_TOKENIZER_BYTES = 7_032_403L
private const val KREA2_URL =
    "https://huggingface.co/gguf-org/krea-2-gguf/resolve/main/krea2_turbo-mxfp4_moe.gguf"
private const val KREA2_LLM_URL =
    "https://huggingface.co/bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf"
private const val KREA2_VAE_URL =
    "https://huggingface.co/Osrivers/wan_2.1_vae.safetensors/resolve/main/wan_2.1_vae.safetensors"
private const val KREA2_TOKENIZER_URL =
    "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct/resolve/main/tokenizer.json"
private const val AURA_KREA2_TAG = "AuraKrea2"
private const val KREA2_MODEL_ID = "krea2_turbo"

internal fun AuraController.installKrea2() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val modelDir = File(File(app.filesDir, "sd_models"), KREA2_MODEL_ID)
        val staging = File(File(app.filesDir, "sd_models"), ".$KREA2_MODEL_ID.downloading").apply { mkdirs() }
        val total = KREA2_BYTES + KREA2_LLM_BYTES + KREA2_VAE_BYTES + KREA2_TOKENIZER_BYTES
        var completed = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                File(staging, "KREA2").createNewFile()
                operation(app.getString(R.string.aura_downloading_krea2), 0f)
                downloadResuming(app, AURA_KREA2_TAG, KREA2_LLM_URL, File(staging, "Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf"), KREA2_LLM_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_krea2), (completed + copied).toFloat() / total)
                }
                completed += File(staging, "Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf").length()
                downloadResuming(app, AURA_KREA2_TAG, KREA2_VAE_URL, File(staging, "wan_2.1_vae.safetensors"), KREA2_VAE_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_krea2), (completed + copied).toFloat() / total)
                }
                completed += File(staging, "wan_2.1_vae.safetensors").length()
                downloadResuming(app, AURA_KREA2_TAG, KREA2_TOKENIZER_URL, File(staging, "tokenizer.json"), KREA2_TOKENIZER_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_krea2), (completed + copied).toFloat() / total)
                }
                completed += File(staging, "tokenizer.json").length()
                downloadResuming(app, AURA_KREA2_TAG, KREA2_URL, File(staging, "krea2_turbo-mxfp4_moe.gguf"), KREA2_BYTES) { copied, _ ->
                    operation(app.getString(R.string.aura_downloading_krea2), (completed + copied).toFloat() / total)
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
            AppLog.write(Log.ERROR, AURA_KREA2_TAG, "Krea 2 Turbo download failed", failure)
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh(modelDir.path)
        } else {
            refresh(null)
            state = state.copy(error = app.getString(R.string.aura_krea2_install_failed))
        }
    }
}
