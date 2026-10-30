package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.mrj.fancyai.sd.DIT_MODEL_ANIMA
import com.mrj.fancyai.sd.DIT_MODEL_SD15
import com.mrj.fancyai.sd.DIT_MODEL_SDXL
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.detectDitModelKind
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AuraLoraFamily(val folder: String, val marker: String, val label: String) {
    SD15("sd15", "SD15", "SD 1.5"),
    SDXL("sdxl", "SDXL", "SDXL"),
    FLUX("flux", "FLUX1", "Flux"),
    ANIMA("anima", "ANIMA", "Anima");

    companion object {
        fun fromFolder(name: String): AuraLoraFamily? =
            entries.firstOrNull { it.folder.equals(name, ignoreCase = true) }
    }
}

internal data class AuraLora(
    val file: File,
    val name: String,
    val family: AuraLoraFamily,
    val strength: Float = 1.0f,
    val enabled: Boolean = false,
) {
    val isDmd2: Boolean get() = name.contains("dmd2", ignoreCase = true)
}

internal fun AuraModel.loraFamily(): AuraLoraFamily? {
    if (type == SdModelType.SDXL) return AuraLoraFamily.SDXL
    if (type == SdModelType.SD15) return AuraLoraFamily.SD15
    if (type == SdModelType.DIT) {
        return when (detectDitModelKind(file)) {
            DIT_MODEL_SDXL -> AuraLoraFamily.SDXL
            DIT_MODEL_SD15 -> AuraLoraFamily.SD15
            DIT_MODEL_ANIMA -> AuraLoraFamily.ANIMA
            6, 1 -> AuraLoraFamily.FLUX
            else -> null
        }
    }
    return null
}

internal fun detectLoraFamily(file: File): AuraLoraFamily? {
    if (!file.isFile || file.length() <= 8L) return null
    return runCatching {
        file.inputStream().use { stream ->
            val headerLenBytes = ByteArray(8)
            if (stream.read(headerLenBytes) == 8) {
                val headerLen = ByteBuffer.wrap(headerLenBytes).order(ByteOrder.LITTLE_ENDIAN).long
                if (headerLen in 10L..10_000_000L) {
                    val headerBytes = ByteArray(headerLen.toInt())
                    var read = 0
                    while (read < headerBytes.size) {
                        val count = stream.read(headerBytes, read, headerBytes.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    val text = String(headerBytes, 0, read, Charsets.UTF_8)
                    when {
                        text.contains("lora_te2_") || text.contains("conditioner.embedders.1") ||
                            text.contains("lora_unet_input_blocks_4") || text.contains("lora_unet_input_blocks_7") ||
                            text.contains("lora_unet_input_blocks_8") || text.contains("adm_in_channels") -> AuraLoraFamily.SDXL
                        text.contains("single_transformer_blocks") || text.contains("double_blocks") ||
                            text.contains("lora_flux") -> AuraLoraFamily.FLUX
                        text.contains("llm_adapter") || text.contains("anima") -> AuraLoraFamily.ANIMA
                        text.contains("lora_te_") || text.contains("cond_stage_model") ||
                            text.contains("lora_unet_down_blocks") || text.contains("lora_unet_input_blocks_11") -> AuraLoraFamily.SD15
                        else -> null
                    }
                } else null
            } else null
        }
    }.getOrNull()
}

internal fun lorasDirectory(context: Context, family: AuraLoraFamily? = null): File {
    val root = File(context.filesDir, "loras")
    return if (family != null) File(root, family.folder).apply { mkdirs() } else root.apply { mkdirs() }
}

internal fun listLorasForModel(context: Context, model: AuraModel?): List<AuraLora> {
    val family = model?.loraFamily() ?: return emptyList()
    val dir = lorasDirectory(context, family)
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val files = dir.listFiles()?.filter {
        it.isFile && it.name.endsWith(".safetensors", ignoreCase = true)
    }.orEmpty()
    return files.map { file ->
        val prefix = "lora.${family.folder}.${file.name}"
        val enabled = preferences.getBoolean("$prefix.enabled", false)
        val strength = preferences.getFloat("$prefix.strength", 1.0f)
        AuraLora(
            file = file,
            name = file.nameWithoutExtension,
            family = family,
            strength = strength,
            enabled = enabled,
        )
    }.sortedBy { it.name.lowercase() }
}

internal fun importLora(
    context: Context,
    uri: Uri,
    suggestedFamily: AuraLoraFamily?,
): AuraLora {
    val tempDir = File(context.cacheDir, "lora_import_${System.currentTimeMillis()}").apply { mkdirs() }
    val tempFile = File(tempDir, "temp.safetensors")
    try {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        val detected = detectLoraFamily(tempFile)
        val family = detected ?: suggestedFamily ?: AuraLoraFamily.SDXL
        val targetDir = lorasDirectory(context, family)
        val rawName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "lora"
        val cleanName = SdModel.sanitize(rawName)
        var destFile = File(targetDir, "$cleanName.safetensors")
        var index = 2
        while (destFile.exists()) {
            destFile = File(targetDir, "$cleanName-$index.safetensors")
            index++
        }
        tempFile.copyTo(destFile, overwrite = true)
        return AuraLora(
            file = destFile,
            name = destFile.nameWithoutExtension,
            family = family,
            strength = 1.0f,
            enabled = false,
        )
    } finally {
        tempDir.deleteRecursively()
    }
}

internal fun deleteLora(context: Context, lora: AuraLora) {
    lora.file.delete()
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val prefix = "lora.${lora.family.folder}.${lora.file.name}"
    preferences.edit {
        remove("$prefix.enabled")
        remove("$prefix.strength")
    }
}

internal fun saveLoraEnabled(context: Context, lora: AuraLora, enabled: Boolean) {
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val prefix = "lora.${lora.family.folder}.${lora.file.name}"
    preferences.edit {
        putBoolean("$prefix.enabled", enabled)
    }
}

internal fun saveLoraStrength(context: Context, lora: AuraLora, strength: Float) {
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val prefix = "lora.${lora.family.folder}.${lora.file.name}"
    preferences.edit {
        putFloat("$prefix.strength", strength)
    }
}
