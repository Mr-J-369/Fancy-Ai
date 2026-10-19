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

internal const val ZIT_BYTES = 3_683_370_944L
private const val ZIT_URL =
    "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/main/z_image_turbo-Q4_0.gguf"
private const val ZIT_FILE = "z_image_turbo-Q4_0.gguf"
private const val AURA_ZIT_TAG = "AuraZit"
private const val ZIT_MODEL_ID = "z_image_turbo_q4_0"

internal fun AuraController.installZitKhv() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val models = File(app.filesDir, "sd_models")
        val modelDir = File(models, ZIT_MODEL_ID)
        val staging = File(models, ".$ZIT_MODEL_ID.downloading").apply { mkdirs() }
        val success = try {
            withContext(Dispatchers.IO) {
                File(staging, "ZIT").createNewFile()
                operation(app.getString(R.string.aura_downloading_zit_q4), 0f)
                downloadResuming(app, AURA_ZIT_TAG, ZIT_URL, File(staging, ZIT_FILE), ZIT_BYTES) { copied, total ->
                    operation(app.getString(R.string.aura_downloading_zit_q4), if (total > 0L) copied.toFloat() / total else null)
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
            state = state.copy(error = app.getString(R.string.aura_zit_q4_install_failed))
        }
    }
}
