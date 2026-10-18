package com.mrj.fancyai.ui.aura

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class CheckpointFamily { SD15, SDXL, SD15_INPAINT, UNKNOWN }

internal data class CheckpointReport(
    val family: CheckpointFamily,
    val tensors: Int,
    val dtype: String,
    val convertible: Boolean,
)

internal fun detectCheckpointFamily(headerKeys: Set<String>, inputChannels: Int?): CheckpointFamily {
    if (headerKeys.any { it.startsWith("conditioner.embedders.") } ||
        "model.diffusion_model.label_emb.0.0.weight" in headerKeys
    ) {
        return CheckpointFamily.SDXL
    }
    if (inputChannels == 9) return CheckpointFamily.SD15_INPAINT
    if (headerKeys.any { it.startsWith("model.diffusion_model.") }) return CheckpointFamily.SD15
    return CheckpointFamily.UNKNOWN
}

internal suspend fun inspectCheckpoint(resolver: ContentResolver, uri: Uri): CheckpointReport? =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)!!.use { input ->
                val lengthBytes = ByteArray(8)
                readFully(input, lengthBytes)
                val headerLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).long
                require(headerLength in 10L..10_000_000L) { "Not a safetensors header" }
                val headerBytes = ByteArray(headerLength.toInt())
                readFully(input, headerBytes)
                val header = Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject
                val entries = header.filterKeys { it != "__metadata__" }
                val channels = entries["model.diffusion_model.input_blocks.0.0.weight"]
                    ?.jsonObject?.get("shape")?.jsonArray
                    ?.getOrNull(1)?.jsonPrimitive?.intOrNull
                val family = detectCheckpointFamily(entries.keys, channels)
                val dtype = entries.values
                    .mapNotNull { (it as? JsonObject)?.get("dtype")?.jsonPrimitive?.contentOrNull }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                CheckpointReport(
                    family = family,
                    tensors = entries.size,
                    dtype = dtype,
                    convertible = family == CheckpointFamily.SD15 || family == CheckpointFamily.SDXL,
                )
            }
        }.getOrNull()
    }

private fun readFully(input: InputStream, buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count <= 0) throw EOFException("Checkpoint header ended early")
        read += count
    }
}
