package com.mrj.fancyai.domain.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.mrj.fancyai.LlamaInference
import com.mrj.fancyai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imports a `.gguf` model picked through the Storage Access Framework and loads it
 * into the native engine.
 *
 * llama.cpp needs a real filesystem path (it does its own C file I/O), and a SAF
 * `content://` URI isn't one — so the picked file is copied into the app's private
 * `models/` directory first, then loaded from there. Engine parameters (backend,
 * threads, context, GPU layers) are applied before load so model-level options take
 * effect on this load.
 */
class ModelLoader(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    /** Copies the picked model into private storage and loads it. Returns the file name on success. */
    suspend fun importAndLoad(
        uri: Uri,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val name = resolveModelName(uri)
            val dest = File(modelsDir, name)

            onProgress("Copying $name…")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output, bufferSize = 1 shl 17) }
            } ?: return@withContext Result.failure(IllegalStateException("Couldn't open the selected file"))

            onProgress("Loading $name…")
            if (load(dest.absolutePath)) {
                Result.success(name)
            } else {
                Result.failure(IllegalStateException("Engine failed to load $name"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "importAndLoad failed", e)
            Result.failure(e)
        }
    }

    /** Reloads the last-used model on launch if its file still exists. */
    suspend fun autoLoadLast(): Boolean = withContext(Dispatchers.IO) {
        val path = settings.modelPath
        if (path.isNotBlank() && File(path).exists() && !LlamaInference.isModelLoaded()) {
            load(path)
        } else false
    }

    fun loadedModelName(): String? =
        LlamaInference.getLoadedModelPath()?.substringAfterLast('/')

    /** Frees the in-memory model (RAM/VRAM). The model file and last-used path remain. */
    fun unload() = LlamaInference.unloadModel()

    /** Imported models available to switch between. */
    fun availableModels(): List<String> =
        modelsDir.listFiles { _, n -> n.endsWith(".gguf", ignoreCase = true) }
            ?.map { it.name }?.sorted() ?: emptyList()

    suspend fun loadByName(name: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(modelsDir, name)
        file.exists() && load(file.absolutePath)
    }

    /**
     * Deletes an imported model file. If it's the currently-loaded model it is unloaded
     * first, and the remembered last-used path is cleared so it isn't auto-loaded next launch.
     */
    suspend fun deleteModel(name: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(modelsDir, name)
        if (LlamaInference.getLoadedModelPath()?.substringAfterLast('/') == name) {
            LlamaInference.unloadModel()
        }
        val deleted = !file.exists() || file.delete()
        if (deleted && settings.modelPath.substringAfterLast('/') == name) {
            settings.modelPath = ""
        }
        deleted
    }

    private fun load(path: String): Boolean {
        // Flash attention is required for a quantized KV cache; for an F16 cache on
        // CPU it tends to slow generation, so leave it off there.
        val kvType = settings.kvCacheType
        LlamaInference.setEngineParams(
            nCtx = settings.contextSize,
            nThreads = settings.threadCount,
            flashAttn = kvType != 0,
            useMmap = true,
            useMlock = false,
            gpuLayers = settings.gpuLayers,
            backend = settings.hardwareBackend,
            kvType = kvType
        )
        val ok = LlamaInference.loadModel(path)
        if (ok) {
            settings.modelPath = path
            // Auto-match the prompt format to the model's embedded chat template so the
            // model emits a stop token the engine recognizes (otherwise it runs to the
            // full token limit every turn — slow, and leaks control tokens into replies).
            val embedded = LlamaInference.getChatTemplate()
            if (embedded.isNotBlank()) {
                ChatTemplate.detectFrom(embedded)?.let { detected ->
                    settings.chatTemplate = detected.id
                    Log.d(TAG, "Auto-selected template '${detected.id}' from embedded model metadata")
                }
            }
        }
        return ok
    }

    private fun resolveModelName(uri: Uri): String {
        val display = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
        val base = (display ?: uri.lastPathSegment ?: "model").substringAfterLast('/')
        return if (base.endsWith(".gguf", ignoreCase = true)) base else "$base.gguf"
    }

    companion object {
        private const val TAG = "ModelLoader"
    }
}
