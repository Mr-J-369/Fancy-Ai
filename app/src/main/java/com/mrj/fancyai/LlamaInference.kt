package com.mrj.fancyai

import android.util.Log

object LlamaInference {
    private const val TAG = "LlamaInference"

    @Volatile var modelLoaded = false
        private set
    @Volatile private var libraryLoaded = false
    @Volatile private var loadedModelPath: String? = null

    // Set by MainActivity so C++ callbacks reach the WebView
    @Volatile var streamBridge: StreamBridge? = null

    interface StreamBridge {
        fun onToken(cbId: Int, token: String)
        fun onDone(cbId: Int)
    }

    init {
        Log.d(TAG, "LlamaInference object initializing...")
        try {
            System.loadLibrary("fancy_ai")
            libraryLoaded = true
            Log.d(TAG, "fancy_ai native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "FATAL: Failed to load fancy_ai library: ${e.message}")
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error during native library load: ${t.message}")
        }
    }

    @Synchronized
    fun loadModel(path: String): Boolean {
        if (!libraryLoaded) return false
        // Idempotent: if this exact model is already loaded, don't reload. Prevents
        // double-loading when the Settings ⚡ button and a background generation's
        // ensureLlamaModelLoaded() race (both call here; @Synchronized serializes
        // them, and the second would otherwise free + reload the same model).
        if (modelLoaded && loadedModelPath == path) {
            Log.d(TAG, "Model already loaded, skipping reload: $path")
            return true
        }
        return try {
            if (nativeLoadModel(path)) {
                modelLoaded = true
                loadedModelPath = path
                Log.d(TAG, "Model loaded: $path")
                true
            } else {
                Log.w(TAG, "nativeLoadModel returned false for: $path")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model: ${e.message}", e)
            false
        }
    }

    fun inferenceStream(prompt: String, maxTokens: Int, cbId: Int,
                        temperature: Float = 0.7f, topK: Int = 40, topP: Float = 0.9f) {
        if (!libraryLoaded || !modelLoaded) {
            Log.w(TAG, "inferenceStream called but engine not ready (lib=$libraryLoaded, model=$modelLoaded)")
            streamBridge?.onDone(cbId)
            return
        }
        try {
            nativeInferenceStream(prompt, maxTokens, cbId, temperature, topK, topP)
        } catch (e: Exception) {
            Log.e(TAG, "inferenceStream error: ${e.message}", e)
            streamBridge?.onDone(cbId)
        }
    }

    fun cancelInference() {
        if (!libraryLoaded) return
        try {
            nativeCancelInference()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel inference: ${t.message}")
        }
    }

    // Updates engine params and recreates the context if a model is already loaded.
    // Context-level changes (nCtx, nThreads, flashAttn) apply immediately.
    // Model-level changes (useMmap, useMlock, gpuLayers) take effect on the next loadModel() call.
    // backend: 0 = CPU, 1 = NPU (Hexagon), 2 = GPU (Vulkan)
    // kvType:  0 = F16, 1 = Q8_0, 2 = Q4_0
    fun setEngineParams(nCtx: Int, nThreads: Int, flashAttn: Boolean,
                        useMmap: Boolean, useMlock: Boolean, gpuLayers: Int, backend: Int = 0, kvType: Int = 0) {
        if (!libraryLoaded) return
        nativeSetEngineParams(nCtx, nThreads, flashAttn, useMmap, useMlock, gpuLayers, backend, kvType)
        if (modelLoaded) {
            Thread {
                cancelInference()
                Thread.sleep(50)
                val ok = nativeReinitContext()
                Log.d(TAG, "Context reinitialized after param change: $ok")
            }.start()
        }
    }

    @Synchronized
    fun unloadModel() {
        if (!libraryLoaded) return
        try {
            if (modelLoaded) {
                nativeUnloadModel()
                modelLoaded = false
                loadedModelPath = null
                Log.d(TAG, "Model unloaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model: ${e.message}", e)
        }
    }

    fun isModelLoaded(): Boolean = modelLoaded
    fun getLoadedModelPath(): String? = loadedModelPath

    // The model's embedded chat template (or "" if none / not loaded).
    fun getChatTemplate(): String = try {
        if (libraryLoaded && modelLoaded) nativeGetChatTemplate() else ""
    } catch (e: Exception) { "" }

    // Called by C++ on the inference thread
    @JvmStatic fun onToken(cbId: Int, token: String) { streamBridge?.onToken(cbId, token) }
    @JvmStatic fun onDone(cbId: Int) { streamBridge?.onDone(cbId) }

    @JvmStatic external fun nativeLoadModel(path: String): Boolean
    @JvmStatic external fun nativeUnloadModel()
    @JvmStatic external fun nativeGetChatTemplate(): String
    @JvmStatic external fun nativeSetEngineParams(
        nCtx: Int, nThreads: Int, flashAttn: Boolean, useMmap: Boolean, useMlock: Boolean, gpuLayers: Int, backend: Int, kvType: Int)
    @JvmStatic external fun nativeReinitContext(): Boolean
    @JvmStatic external fun nativeInferenceStream(
        prompt: String, maxTokens: Int, cbId: Int,
        temperature: Float, topK: Int, topP: Float)
    @JvmStatic external fun nativeCancelInference()
}
