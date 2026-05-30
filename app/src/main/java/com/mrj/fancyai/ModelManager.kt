package com.mrj.fancyai

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelManager(
    private val context: Context,
    private val sendToJs: (String) -> Unit
) {
    private val prefs get() = context.getSharedPreferences("fancy_ai", Context.MODE_PRIVATE)
    private val filesDir get() = context.filesDir
    private val modelsDir get() = File(filesDir, "models").apply { mkdirs() }

    init {
        wireStreamBridge()
        LlamaInference.isModelLoaded()
    }

    private fun wireStreamBridge() {
        LlamaInference.streamBridge = object : LlamaInference.StreamBridge {
            override fun onToken(cbId: Int, token: String) {
                val escaped = token
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                sendToJs("(function(){var f=window._llamaToken&&window._llamaToken[$cbId];if(f)f(\"$escaped\");})()")
            }
            override fun onDone(cbId: Int) {
                sendToJs("(function(){var f=window._llamaDone&&window._llamaDone[$cbId];if(f){delete window._llamaToken[$cbId];delete window._llamaDone[$cbId];f();}})()")
            }
        }
    }

    fun loadModel(path: String): Boolean = LlamaInference.loadModel(path)

    fun loadModelByName(fileName: String): Boolean {
        val file = File(modelsDir, fileName)
        val ok = if (file.exists()) LlamaInference.loadModel(file.absolutePath) else false
        if (ok) {
            prefs.edit().putString("last_model_path", file.absolutePath).apply()
        }
        return ok
    }

    fun copyAndLoadFromUri(uri: Uri, fileName: String): Boolean {
        return try {
            val destFile = File(modelsDir, fileName)
            val input = context.contentResolver.openInputStream(uri) ?: run {
                Log.e("ModelManager", "Failed to open input stream for: $uri")
                return false
            }
            input.use { stream ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(131072)
                    var n: Int
                    var total = 0L
                    while (stream.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        total += n
                        if (total % (256L * 1024 * 1024) == 0L)
                            Log.d("ModelManager", "Copied ${total / (1024 * 1024)}MB")
                    }
                    Log.d("ModelManager", "URI copied: ${total / (1024 * 1024)}MB → $destFile")
                }
            }
            val ok = LlamaInference.loadModel(destFile.absolutePath)
            if (ok) {
                prefs.edit().putString("last_model_path", destFile.absolutePath).apply()
            }
            ok
        } catch (e: Exception) {
            Log.e("ModelManager", "copyAndLoadFromUri failed: ${e.message}", e)
            false
        }
    }

    fun unloadModel() = LlamaInference.unloadModel()

    fun unloadModelAsync() {
        LlamaInference.cancelInference()
        Thread { LlamaInference.unloadModel() }.start()
    }

    fun isModelLoaded(): Boolean = LlamaInference.isModelLoaded()

    fun getLoadedModelPath(): String? = LlamaInference.getLoadedModelPath()

    fun getChatTemplate(): String = LlamaInference.getChatTemplate()

    fun listModels(): String {
        return try {
            if (!modelsDir.exists() || !modelsDir.isDirectory) return "[]"
            val files = modelsDir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()
            val names = files.map { it.name.replace("\"", "\\\"") }
            "[${names.joinToString(",") { "\"$it\"" }}]"
        } catch (_: Exception) {
            "[]"
        }
    }

    fun deleteModel(fileName: String): Boolean {
        return try {
            val file = File(modelsDir, fileName)
            if (!file.exists()) return false
            if (LlamaInference.getLoadedModelPath() == file.absolutePath) {
                LlamaInference.unloadModel()
            }
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun inferenceAsync(
        prompt: String, maxTokens: Int, callbackId: Int,
        temperature: Float, topK: Int, topP: Float
    ) {
        Thread {
            LlamaInference.inferenceStream(prompt, maxTokens, callbackId, temperature, topK, topP)
        }.start()
    }

    fun cancelInference() = LlamaInference.cancelInference()

    fun setEngineParams(
        nCtx: Int, nThreads: Int, flashAttn: Boolean,
        useMmap: Boolean, useMlock: Boolean, gpuLayers: Int, backend: Int, kvType: Int
    ) {
        LlamaInference.setEngineParams(nCtx, nThreads, flashAttn, useMmap, useMlock, gpuLayers, backend, kvType)
    }
}
