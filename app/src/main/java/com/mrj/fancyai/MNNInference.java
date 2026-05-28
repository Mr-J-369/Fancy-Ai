package com.mrj.fancyai;

import android.util.Log;

/**
 * MNN Inference wrapper — loads .mnn models and runs inference via JNI.
 * Gracefully handles errors; falls back to cloud APIs if model unavailable.
 */
public class MNNInference {
    private static final String TAG = "MNNInference";
    private static boolean modelLoaded = false;
    private static String loadedModelPath = null;

    static {
        try {
            System.loadLibrary("mnncore");
            System.loadLibrary("llm");
            Log.d(TAG, "MNN native libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load MNN native libraries: " + e.getMessage());
        }
    }

    /**
     * Load a .mnn model file. Returns true on success.
     */
    public static synchronized boolean loadModel(String path) {
        try {
            if (nativeLoadModel(path)) {
                modelLoaded = true;
                loadedModelPath = path;
                Log.d(TAG, "Model loaded: " + path);
                return true;
            } else {
                Log.w(TAG, "Native loadModel returned false for: " + path);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading model: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Run inference on a prompt. Returns the generated text or null on error.
     */
    public static String inference(String prompt, int maxTokens) {
        if (!modelLoaded) {
            Log.w(TAG, "inference() called but model not loaded");
            return null;
        }

        try {
            return nativeInference(prompt, maxTokens);
        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Unload the model and free memory.
     */
    public static synchronized void unloadModel() {
        try {
            if (modelLoaded) {
                nativeUnloadModel();
                modelLoaded = false;
                loadedModelPath = null;
                Log.d(TAG, "Model unloaded");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unloading model: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a model is currently loaded.
     */
    public static boolean isModelLoaded() {
        return modelLoaded;
    }

    /**
     * Get the path of the currently loaded model.
     */
    public static String getLoadedModelPath() {
        return loadedModelPath;
    }

    // ===== Native JNI methods (implemented in C++) =====
    // libmnncore.so and libllm.so provide these symbols
    private static native boolean nativeLoadModel(String path);
    private static native String nativeInference(String prompt, int maxTokens);
    private static native void nativeUnloadModel();
}
