package com.mrj.fancyai.ui.settings

import android.content.Context
import androidx.core.content.edit
import com.mrj.fancyai.engine.GgufModel
import com.mrj.fancyai.engine.LiteRtModel
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmRuntime
import java.io.File

internal object LlmExternalModels {
    fun copyIntoStorage(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COPY, true)

    fun saveCopyIntoStorage(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_COPY, value) }
    }

    fun isLinked(context: Context, path: String): Boolean = path in linkedPaths(context)

    fun merged(context: Context): List<LocalLlmModel> {
        val store = LlmSettingsStore.localModels(context)
        val linked = linkedPaths(context).mapNotNull { path ->
            runCatching { store.fromFile(File(path)) }.getOrNull() ?: shellModel(path)
        }
        return (store.installed() + linked)
            .sortedWith(compareBy(LocalLlmModel::runtime, LocalLlmModel::name))
    }

    fun link(context: Context, path: String): LocalLlmModel {
        val model = LlmSettingsStore.localModels(context).fromFile(File(path))
        val internalDir = when (model.runtime) {
            LocalLlmRuntime.LITERT -> LlmSettingsStore.liteRtDirectory(context)
            LocalLlmRuntime.LLAMA -> LlmSettingsStore.llamaDirectory(context)
        }
        if (File(model.path).parentFile?.canonicalFile != internalDir.canonicalFile) {
            prefs(context).edit { putStringSet(KEY_PATHS, linkedPaths(context) + model.path) }
        }
        LlmSettingsStore.selectModel(context, model.path)
        return model
    }

    fun unlink(context: Context, path: String) {
        prefs(context).edit { putStringSet(KEY_PATHS, linkedPaths(context) - path) }
    }

    private fun shellModel(path: String): LocalLlmModel? {
        val name = File(path).name
        return when {
            name.endsWith(".litertlm", ignoreCase = true) -> LiteRtModel(name, path, false)
            name.endsWith(".gguf", ignoreCase = true) -> GgufModel(name, path)
            else -> null
        }
    }

    private fun linkedPaths(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PATHS, emptySet()).orEmpty()
            .filter { it.isNotBlank() }.toSet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "external_llm_models"
    private const val KEY_PATHS = "paths"
    private const val KEY_COPY = "copy_into_storage"
}
