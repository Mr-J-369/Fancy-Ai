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
internal const val CHROMA_T5_BYTES = 2_679_106_848L
private const val CHROMA_T5_URL =
    "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/t5-v1_1-xxl-encoder-Q4_0.gguf"
private const val AURA_CHROMA_TAG = "AuraChroma"

internal fun isChromaComponentsInstalled(context: Context): Boolean =
    File(File(context.filesDir, "chroma_components"), CHROMA_T5_FILE).length() > 0L

internal fun AuraController.installChromaComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val target = File(File(app.filesDir, "chroma_components").apply { mkdirs() }, CHROMA_T5_FILE)
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_chroma_components), 0f)
                downloadDirect(app, CHROMA_T5_URL, target, CHROMA_T5_BYTES) { read, total ->
                    operation(
                        app.getString(R.string.aura_downloading_chroma_components),
                        if (total > 0L) read.toFloat() / total else null,
                    )
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_CHROMA_TAG, "Chroma T5 download failed", failure)
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
