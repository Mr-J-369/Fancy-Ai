package com.mrj.fancyai.ui.characters

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.lorebook.deleteLorebookForCharacter
import com.mrj.fancyai.ui.lorebook.importCharacterLorebook
import com.mrj.fancyai.ui.settings.ProAccess
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.exportDocument
import com.mrj.fancyai.util.writeProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(),
    0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte(),
)
private const val TEXT_CHUNK = "tEXt"
private const val IEND_CHUNK = "IEND"
private const val V2_PNG_KEY = "chara"
private const val V3_PNG_KEY = "ccv3"
private const val MAX_PNG_BYTES = 64L * 1024L * 1024L
private const val MAX_TEXT_CHUNK_BYTES = 16L * 1024L * 1024L
private const val MAX_ENCODED_JSON_BYTES = 16 * 1024 * 1024
private const val MAX_JSON_BYTES = 8 * 1024 * 1024
private const val BUFFER_SIZE = 16 * 1024

private val charaJsonFormat = Json { ignoreUnknownKeys = true; prettyPrint = true }

internal fun importCharacterCard(context: Context, uri: Uri): CharacterCard = synchronized(ProAccess.charactersLock) {
    val png = context.contentResolver.openInputStream(uri).use { input ->
        (input != null) && ByteArray(PNG_SIGNATURE.size).also(input::readFully).contentEquals(PNG_SIGNATURE)
    }
    val json = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input)
        if (png) readPngCard(input) else input.readBounded().toString(StandardCharsets.UTF_8)
    }
    val imported = parseCharacter(json)
    val folder = File(File(context.filesDir, "characters"), UUID.randomUUID().toString()).apply { mkdirs() }
    try {
        writeProperties(File(folder, CHARACTER_FILE), imported.toDraft().toProperties())
        if (png) {
            val avatar = File(folder, AVATAR_FILE)
            copyResizedImage(context, uri, avatar, AVATAR_MAX_EDGE)
            avatar.copyTo(File(folder, BACKGROUND_FILE), overwrite = true)
        }
        val saved = checkNotNull(readCharacter(folder))
        importCharacterLorebook(context, saved.id, json)
        saved
    } catch (error: Throwable) {
        deleteLorebookForCharacter(context, folder.name)
        folder.deleteRecursively()
        throw error
    }
}

internal fun exportCharacterJson(context: Context, character: CharacterCard, uri: Uri) {
    exportDocument(context, uri) { output ->
        output.write(characterCardJson(character).toByteArray(StandardCharsets.UTF_8))
    }
}

internal fun exportCharacterPng(context: Context, character: CharacterCard, uri: Uri) {
    val avatar = File(File(File(context.filesDir, "characters"), character.id), AVATAR_FILE)
    val bitmap = decodeImage(context, Uri.fromFile(avatar))
    val temporaryPng = File.createTempFile("character-card-", ".png", context.cacheDir)
    try {
        temporaryPng.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val encodedCard = Base64.encodeToString(
            characterCardJson(character).toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        exportDocument(context, uri) { output ->
            temporaryPng.inputStream().use { input -> writePngCard(input, output, encodedCard) }
        }
    } finally {
        bitmap.recycle()
        temporaryPng.delete()
    }
}

internal fun characterExportName(context: Context, character: CharacterCard): String = character.name
    .replace(Regex("[^\\p{L}\\p{N}._ -]"), "")
    .trim()
    .ifBlank { context.getString(R.string.label_character).lowercase() }

private fun readPngCard(source: InputStream): String {
    val input = DataInputStream(source)
    val signature = ByteArray(PNG_SIGNATURE.size).also(input::readFully)
    require(signature.contentEquals(PNG_SIGNATURE))
    var v2: String? = null
    var v3: String? = null
    var consumed = PNG_SIGNATURE.size.toLong()
    while (true) {
        val length = input.readInt().toLong() and 0xffffffffL
        val typeBytes = ByteArray(4).also(input::readFully)
        val type = typeBytes.toString(StandardCharsets.US_ASCII)
        consumed += 12L + length
        require(consumed <= MAX_PNG_BYTES)
        val data = if ((type == TEXT_CHUNK) && (length <= MAX_TEXT_CHUNK_BYTES)) {
            ByteArray(length.toInt()).also(input::readFully)
        } else {
            input.skipFully(length)
            null
        }
        input.skipFully(4)
        if (type == IEND_CHUNK) break
        if (data == null) continue
        val separator = data.indexOf(0)
        if (separator <= 0) continue
        val key = data.copyOfRange(0, separator).toString(StandardCharsets.ISO_8859_1)
        if (key != V2_PNG_KEY && key != V3_PNG_KEY) continue
        val encoded = data.copyOfRange(separator + 1, data.size).toString(StandardCharsets.ISO_8859_1)
        require(encoded.length <= MAX_ENCODED_JSON_BYTES)
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        require(decoded.size <= MAX_JSON_BYTES)
        val json = decoded.toString(StandardCharsets.UTF_8)
        if (key == V3_PNG_KEY) v3 = json else v2 = json
    }
    return v3 ?: v2 ?: error("PNG does not contain a character card")
}

private fun parseCharacter(jsonText: String): CharacterCard {
    val spec = runCatching { charaJsonFormat.decodeFromString<CharacterCardSpec>(jsonText) }.getOrNull()
    val data = spec?.data
    val name = (data?.name?.takeIf(String::isNotBlank)
        ?: runCatching { charaJsonFormat.parseToJsonElement(jsonText).jsonObject["name"]?.jsonPrimitive?.content }.getOrNull())
        ?.trim()?.capitalizeFirstVisibleLetter().orEmpty()
    require(name.isNotBlank()) { "Character card missing name" }

    val fancy = data?.extensions?.get(FANCY_EXTENSION)
    return CharacterCard(
        id = "",
        name = name,
        handle = fancy?.handle?.trim().orEmpty(),
        personality = (data?.personality ?: "").trim().capitalizeFirstVisibleLetter(),
        description = (data?.description ?: "").trim().capitalizeFirstVisibleLetter(),
        scene = (data?.scenario ?: "").trim().capitalizeFirstVisibleLetter(),
        firstMessage = (data?.firstMessage ?: "").trim().capitalizeFirstVisibleLetter(),
        appearance = fancy?.appearance?.trim()?.capitalizeFirstVisibleLetter().orEmpty(),
        rebbitEnabled = fancy?.rebbitEnabled ?: true,
        ustagramEnabled = fancy?.ustagramEnabled ?: true,
        yEnabled = fancy?.yEnabled ?: true,
        dareEnabled = fancy?.dareEnabled ?: true,
    )
}

private fun characterCardJson(character: CharacterCard): String {
    val spec = CharacterCardSpec(
        data = CharacterCardData(
            name = character.name,
            description = character.description,
            personality = character.personality,
            scenario = character.scene,
            firstMessage = character.firstMessage,
            extensions = mapOf(
                FANCY_EXTENSION to FancyCharacterExtension(
                    handle = character.handle,
                    appearance = character.appearance,
                    rebbitEnabled = character.rebbitEnabled,
                    ustagramEnabled = character.ustagramEnabled,
                    yEnabled = character.yEnabled,
                    dareEnabled = character.dareEnabled,
                ),
            ),
        ),
    )
    return charaJsonFormat.encodeToString(spec)
}

private fun writePngCard(source: InputStream, destination: OutputStream, encodedCard: String) {
    val input = DataInputStream(source)
    val output = DataOutputStream(destination)
    val signature = ByteArray(PNG_SIGNATURE.size).also(input::readFully)
    output.write(signature)
    while (true) {
        val length = input.readInt()
        val unsignedLength = length.toLong() and 0xffffffffL
        val type = ByteArray(4).also(input::readFully)
        if (type.toString(StandardCharsets.US_ASCII) == IEND_CHUNK) {
            writeTextChunk(output, encodedCard)
        }
        output.writeInt(length)
        output.write(type)
        input.copyExactly(output, unsignedLength)
        output.writeInt(input.readInt())
        if (type.toString(StandardCharsets.US_ASCII) == IEND_CHUNK) break
    }
}

private fun writeTextChunk(output: DataOutputStream, value: String) {
    val type = TEXT_CHUNK.toByteArray(StandardCharsets.US_ASCII)
    val data = "$V2_PNG_KEY\u0000$value".toByteArray(StandardCharsets.ISO_8859_1)
    val crc = CRC32().apply {
        update(type)
        update(data)
    }
    output.writeInt(data.size)
    output.write(type)
    output.write(data)
    output.writeInt(crc.value.toInt())
}

private fun InputStream.readBounded(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_JSON_BYTES)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun InputStream.readFully(buffer: ByteArray) {
    var total = 0
    while (total < buffer.size) {
        val count = read(buffer, total, buffer.size - total)
        require(count >= 0) { "Unexpected end of PNG stream" }
        total += count
    }
}

private fun DataInputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0L) {
        val skipped = skip(remaining)
        require(skipped > 0L) { "Unexpected end of PNG stream" }
        remaining -= skipped
    }
}

private fun InputStream.copyExactly(output: OutputStream, length: Long) {
    val buffer = ByteArray(BUFFER_SIZE)
    var remaining = length
    while (remaining > 0L) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(count >= 0) { "Unexpected end of PNG stream" }
        output.write(buffer, 0, count)
        remaining -= count.toLong()
    }
}
