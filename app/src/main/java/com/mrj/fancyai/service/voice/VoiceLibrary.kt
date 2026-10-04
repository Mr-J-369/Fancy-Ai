package com.mrj.fancyai.service.voice

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.util.writeAtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal data class SavedVoice(val model: String, val id: String, val name: String) {
    val key: String get() = "$model:$id"
}

internal object VoiceLibrary {
    fun voices(context: Context): List<SavedVoice> {
        val builtIn = context.resources.getStringArray(R.array.voice_kokoro_speakers).mapIndexed { id, name ->
            SavedVoice("kokoro", id.toString(), name)
        }
        val custom = context.getSharedPreferences("voice", Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            if (key.startsWith("voice_name@") && value is String) SavedVoice("pocket", key.substringAfter('@'), value) else null
        }.sortedBy { it.name.lowercase() }
        return custom + SavedVoice("kokoro", "auto", context.getString(R.string.voice_follow_language)) + builtIn +
            List(10) { id -> SavedVoice(LocalVoicePack.SUPERTONIC.id, id.toString(),
                context.getString(R.string.voice_supertonic_speaker, id + 1)) }
    }

    fun characterVoice(context: Context, characterId: String): SavedVoice? {
        val key = context.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("character_voice@$characterId", "").orEmpty()
        if (key.isBlank()) return null
        return voices(context).firstOrNull { it.key == key }
            ?: error("Assigned voice is unavailable")
    }

    fun assign(context: Context, characterId: String, key: String) {
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit {
            if (key.isBlank()) remove("character_voice@$characterId") else putString("character_voice@$characterId", key)
        }
    }

    fun moveAssignment(context: Context, from: String, to: String) {
        if (from == to) return
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit {
            putString("character_voice@$to", context.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("character_voice@$from", "").orEmpty())
            remove("character_voice@$from")
        }
    }

    fun importDraft(context: Context, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_REFERENCE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
            ?: error("Cannot read voice sample")
        writeDraft(context, bytes)
    }

    fun discardDraft(context: Context) {
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit { remove("voice_draft_name") }
        java.nio.file.Files.deleteIfExists(File(context.filesDir, "voice/voices/draft.wav").toPath())
    }

    fun writeDraft(context: Context, bytes: ByteArray) {
        validateReference(bytes)
        writeAtomicFile(File(context.filesDir, "voice/voices/draft.wav"), bytes)
    }

    fun save(context: Context): SavedVoice {
        val name = context.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("voice_draft_name", "").orEmpty().trim()
        require(name.isNotEmpty())
        val source = File(context.filesDir, "voice/voices/draft.wav")
        val voice = SavedVoice("pocket", UUID.randomUUID().toString(), name)
        val destination = File(context.filesDir, "voice/voices/${voice.id}.wav")
        source.copyTo(destination)
        val update = context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
        update.putString("voice_name@${voice.id}", name).remove("voice_draft_name")
        if (!update.commit()) {
            destination.delete()
            error("Cannot save voice")
        }
        source.delete()
        return voice
    }

    @SuppressLint("UseKtx")
    fun delete(context: Context, voice: SavedVoice) {
        require(voice.model == "pocket")
        require(UUID.fromString(voice.id).toString() == voice.id)
        val values = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val edit = values.edit()
        edit.remove("voice_name@${voice.id}")
        values.all.forEach { (key, value) ->
            if ((key.startsWith("character_voice@") && value == voice.key) ||
                (key == "tts_voice@LOCAL@pocket" && value == voice.id)) edit.remove(key)
        }
        java.nio.file.Files.deleteIfExists(File(context.filesDir, "voice/voices/${voice.id}.wav").toPath())
        check(edit.commit())
    }

    private fun validateReference(bytes: ByteArray) {
        require(bytes.size in 44..MAX_REFERENCE_BYTES)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE")
        var offset = 12
        var rate = 0
        var samples = 0
        while (offset + 8 <= bytes.size) {
            val size = input.getInt(offset + 4)
            require(size >= 0 && size <= bytes.size - offset - 8)
            when (tag(offset)) {
                "fmt " -> {
                    require(size >= 16 && input.getShort(offset + 8).toInt() == 1)
                    require(input.getShort(offset + 10).toInt() == 1)
                    rate = input.getInt(offset + 12)
                    require(rate in 8000..48000 && input.getShort(offset + 22).toInt() == 16)
                }
                "data" -> { require(size % 2 == 0); samples += size / 2 }
            }
            offset += 8 + size + size % 2
        }
        require(rate > 0 && samples.toDouble() / rate in 3.0..15.1)
    }

    private const val MAX_REFERENCE_BYTES = 1_500_000
}
