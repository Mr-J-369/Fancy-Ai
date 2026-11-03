package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.mrj.fancyai.sd.DIT_MODEL_ANIMA
import com.mrj.fancyai.sd.DIT_MODEL_MOSS
import com.mrj.fancyai.sd.DIT_MODEL_SD15
import com.mrj.fancyai.sd.DIT_MODEL_SDXL
import com.mrj.fancyai.sd.DIT_MODEL_Z_IMAGE
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.detectDitModelKind
import com.mrj.fancyai.ui.settings.documentInfo
import java.io.File
import java.io.FileOutputStream

internal enum class AuraLoraFamily(val folder: String, val marker: String, val label: String) {
    SD15("sd15", "SD15", "SD 1.5"),
    SDXL("sdxl", "SDXL", "SDXL"),
    FLUX("flux", "FLUX1", "Flux"),
    ANIMA("anima", "ANIMA", "Anima"),
    Z_IMAGE("z_image", "Z_IMAGE", "Z-Image"),
    MOSS("moss", "MOSS", "MOSS"),
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
            DIT_MODEL_Z_IMAGE -> AuraLoraFamily.Z_IMAGE
            DIT_MODEL_MOSS -> AuraLoraFamily.MOSS
            6, 1 -> AuraLoraFamily.FLUX
            else -> null
        }
    }
    return null
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
        val targetDir = lorasDirectory(context, targetFamily)
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
            family = targetFamily,
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
