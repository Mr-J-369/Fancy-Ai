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
import com.mrj.fancyai.ui.settings.documentInfo
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
    val lowerName = file.name.lowercase()
    if (lowerName.contains("flux")) return AuraLoraFamily.FLUX
    if (lowerName.contains("anima")) return AuraLoraFamily.ANIMA
    if (lowerName.contains("sdxl") || lowerName.contains("xl")) return AuraLoraFamily.SDXL
    if (lowerName.contains("sd15") || lowerName.contains("sd1.5") || lowerName.contains("v1-5")) return AuraLoraFamily.SD15

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
                        text.contains("single_transformer_blocks") || text.contains("double_blocks") ||
                            text.contains("lora_flux") -> AuraLoraFamily.FLUX
                        text.contains("llm_adapter") || text.contains("anima") -> AuraLoraFamily.ANIMA
                        text.contains("lora_te2_") || text.contains("conditioner.embedders.1") ||
                            text.contains("text_encoders.1") || text.contains("text_encoder_2") ||
                            text.contains("lora_unet_input_blocks_4") || text.contains("lora_unet_input_blocks_7") ||
                            text.contains("lora_unet_input_blocks_8") || text.contains("adm_in_channels") ||
                            text.contains("label_emb") -> AuraLoraFamily.SDXL
                        text.contains("down_blocks_3") || text.contains("down_blocks.3") ||
                            text.contains("lora_unet_input_blocks_11") || text.contains("lora_unet_input_blocks_9") ||
                            text.contains("cond_stage_model") -> AuraLoraFamily.SD15
                        text.contains("lora_unet_down_blocks") || text.contains("down_blocks") -> AuraLoraFamily.SDXL
                        text.contains("lora_te_") -> AuraLoraFamily.SD15
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

internal fun listLoras(context: Context, family: AuraLoraFamily): List<AuraLora> {
    val dir = lorasDirectory(context, family)
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val files = dir.listFiles()?.filter {
        it.isFile && it.name.endsWith(".safetensors", ignoreCase = true)
    }.orEmpty()
    return files.map { file ->
        val prefix = "lora.${family.folder}.${file.name}"
        val enabled = preferences.getBoolean("$prefix.enabled", false)
        val defaultStrength = if (file.name.contains("dmd2", ignoreCase = true)) 0.7f else 1.0f
        val strength = preferences.getFloat("$prefix.strength", defaultStrength)
        AuraLora(
            file = file,
            name = file.nameWithoutExtension,
            family = family,
            strength = strength,
            enabled = enabled,
        )
    }.sortedBy { it.name.lowercase() }
}

internal fun listLorasForModel(context: Context, model: AuraModel?): List<AuraLora> {
    val family = model?.loraFamily() ?: return emptyList()
    return listLoras(context, family)
}

internal fun importLora(
    context: Context,
    uri: Uri,
    targetFamily: AuraLoraFamily,
): AuraLora {
    val tempDir = File(context.cacheDir, "lora_import_${System.currentTimeMillis()}").apply { mkdirs() }
    val tempFile = File(tempDir, "temp.safetensors")
    try {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        val family = targetFamily
        val targetDir = lorasDirectory(context, family)
        val docName = documentInfo(context, uri, "lora.safetensors").name
        val rawName = docName.substringBeforeLast('.').ifBlank { "lora" }
        val cleanName = SdModel.sanitize(rawName).ifBlank { "lora" }
        var destFile = File(targetDir, "$cleanName.safetensors")
        var index = 2
        while (destFile.exists()) {
            destFile = File(targetDir, "$cleanName-$index.safetensors")
            index++
        }
        tempFile.copyTo(destFile, overwrite = true)
        val initialStrength = if (cleanName.contains("dmd2", ignoreCase = true)) 0.7f else 1.0f
        return AuraLora(
            file = destFile,
            name = destFile.nameWithoutExtension,
            family = family,
            strength = initialStrength,
            enabled = false,
        )
    } finally {
        tempDir.deleteRecursively()
    }
}

internal fun moveLora(context: Context, lora: AuraLora, targetFamily: AuraLoraFamily): AuraLora {
    if (lora.family == targetFamily) return lora
    val targetDir = lorasDirectory(context, targetFamily)
    var destFile = File(targetDir, lora.file.name)
    var index = 2
    while (destFile.exists()) {
        destFile = File(targetDir, "${lora.file.nameWithoutExtension}-$index.safetensors")
        index++
    }
    lora.file.renameTo(destFile)
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val oldPrefix = "lora.${lora.family.folder}.${lora.file.name}"
    val newPrefix = "lora.${targetFamily.folder}.${destFile.name}"
    val enabled = preferences.getBoolean("$oldPrefix.enabled", false)
    val strength = preferences.getFloat("$oldPrefix.strength", lora.strength)
    preferences.edit {
        remove("$oldPrefix.enabled")
        remove("$oldPrefix.strength")
        putBoolean("$newPrefix.enabled", enabled)
        putFloat("$newPrefix.strength", strength)
    }
    return AuraLora(
        file = destFile,
        name = destFile.nameWithoutExtension,
        family = targetFamily,
        strength = strength,
        enabled = enabled,
    )
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
