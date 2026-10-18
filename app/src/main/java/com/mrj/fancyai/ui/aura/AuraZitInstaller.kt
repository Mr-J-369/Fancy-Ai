package com.mrj.fancyai.ui.aura

import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal const val ZIT_BYTES = 3_683_370_944L
private const val ZIT_URL =
    "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/main/z_image_turbo-Q4_0.gguf"
private const val ZIT_LLM_URL =
    "https://huggingface.co/zhiyuanasad/z_image_turbo_adreno/resolve/main/llm.gguf"
private const val ZIT_LLM_BYTES = 2_262_670_048L
private const val AURA_ZIT_TAG = "AuraZit"
private const val ZIT_DOWNLOAD_ATTEMPTS = 5
private const val ZIT_MODEL_ID = "z_image_turbo_q4_0"

private suspend fun downloadResuming(
    controller: AuraController,
    url: String,
    target: File,
    expectedSize: Long,
    completed: Long,
    total: Long,
) {
    var failure: Exception? = null
    repeat(ZIT_DOWNLOAD_ATTEMPTS) { attempt ->
        try {
            downloadDirect(controller.app, url, target, expectedSize) { copied, _ ->
                controller.operation(controller.app.getString(R.string.aura_downloading_zit_khv), (completed + copied).toFloat() / total)
            }
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            AppLog.write(Log.WARN, AURA_ZIT_TAG, "Download attempt ${attempt + 1}/$ZIT_DOWNLOAD_ATTEMPTS stalled for ${target.name}, resuming", error)
            delay((2_000L * (attempt + 1)).milliseconds)
        }
    }
    throw checkNotNull(failure) { "Download failed for ${target.name}" }
}

internal fun AuraController.installZitKhv() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val models = File(app.filesDir, "sd_models")
        val modelDir = File(models, ZIT_MODEL_ID)
        val staging = File(models, ".$ZIT_MODEL_ID.downloading").apply { mkdirs() }
        val total = ZIT_BYTES + ZIT_LLM_BYTES
        var completed = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                File(staging, "ZIT").createNewFile()
                operation(app.getString(R.string.aura_downloading_zit_khv), 0f)
                downloadResuming(this@installZitKhv, ZIT_LLM_URL, File(staging, "llm.gguf"), ZIT_LLM_BYTES, completed, total)
                completed += File(staging, "llm.gguf").length()
                downloadResuming(this@installZitKhv, ZIT_URL, File(staging, "dit.gguf"), ZIT_BYTES, completed, total)
                SdModel.validateImported(staging)
                modelDir.deleteRecursively()
                check(staging.renameTo(modelDir)) { "Could not finalize model installation" }
            }
            true
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_ZIT_TAG, "Z-Image Turbo Q4_0 download failed", failure)
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh(modelDir.path)
        } else {
            refresh(null)
            state = state.copy(error = app.getString(R.string.aura_zit_khv_install_failed))
        }
    }
}
