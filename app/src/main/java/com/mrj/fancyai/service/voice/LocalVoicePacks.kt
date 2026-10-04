package com.mrj.fancyai.service.voice

import android.content.Context
import com.mrj.fancyai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class LocalVoicePack(
    val id: String,
    val release: String,
    val directory: String,
    val bytes: Long,
    val required: List<String>,
    val title: Int,
    val detail: Int,
) {
    POCKET("pocket", "tts-models", "sherpa-onnx-pocket-tts-int8-2026-01-26", 98336520,
        listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json"),
        R.string.voice_pack_pocket, R.string.voice_pack_pocket_detail),
    KOKORO("kokoro", "tts-models", "kokoro-multi-lang-v1_0", 349906910,
        listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt", "espeak-ng-data/phontab"),
        R.string.voice_pack_kokoro, R.string.voice_pack_kokoro_detail),
    SUPERTONIC("supertonic3", "tts-models", "sherpa-onnx-supertonic-3-tts-int8-2026-05-11", 128774318,
        listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"),
        R.string.voice_pack_supertonic, R.string.voice_pack_supertonic_detail),
    WHISPER("whisper", "asr-models", "sherpa-onnx-whisper-tiny", 116204861,
        listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt"),
        R.string.voice_pack_whisper, R.string.voice_pack_whisper_detail),
    ZIPFORMER("two-pass-en", "asr-models", "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", 127887156,
        listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
        R.string.voice_pack_two_pass, R.string.voice_pack_two_pass_detail);

    fun installed(context: Context): Boolean = File(context.filesDir, "voice/models/$directory").let { root ->
        File(root, ".verified").isFile && required.all { File(root, it).length() > 0L }
    }

    suspend fun unpack(archive: File, staging: File, progress: (Float) -> Unit): File {
        archive.inputStream().buffered().use { input ->
            val compressed = BZip2CompressorInputStream(input)
            val tar = TarArchiveInputStream(compressed)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.name != directory && !entry.name.startsWith("$directory/")) continue
                val destination = File(staging, entry.name)
                if (entry.isDirectory) {
                    destination.mkdirs()
                    continue
                }
                val relative = entry.name.removePrefix("$directory/")
                // Upstream bundles include full-precision duplicates and demo audio.
                val keep = !relative.startsWith("test_wavs/") &&
                    (!relative.endsWith(".onnx") || relative in required)
                val output = if (keep) {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().buffered()
                } else null
                output.use {
                    copyVoicePackBytes(tar, output, progress = {
                        progress(compressed.compressedCount.toFloat() / bytes)
                    })
                }
            }
        }

        val extracted = File(staging, directory)
        File(extracted, ".verified").writeText("1")
        return extracted
    }

    companion object {
        val supertonicLanguageCodes: List<String> = listOf(
            "ar", "bg", "hr", "cs", "da", "nl", "en", "et",
            "fi", "fr", "de", "el", "hi", "hu", "id", "it",
            "ja", "ko", "lv", "lt", "pl", "pt", "ro", "ru",
            "sk", "sl", "es", "sv", "tr", "uk", "vi",
        )

        fun ready(context: Context, id: String): Boolean = entries.firstOrNull { it.id == id }
            ?.let { it.installed(context) && (it != ZIPFORMER || WHISPER.installed(context)) } == true
    }
}

/** Downloads are private, bounded and published only after the complete archive is verified. */
internal class LocalVoiceDownloads {
    private val active = AtomicReference<Call?>()
    fun cancel() { active.get()?.cancel() }

    private companion object {
        val lock = Mutex()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    }

    suspend fun delete(context: Context, pack: LocalVoicePack) = lock.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(context.filesDir, "voice/models/${pack.directory}")
            if (folder.exists()) {
                File(folder, ".verified").delete()
                check(folder.deleteRecursively())
            }
        }
    }

    suspend fun install(context: Context, pack: LocalVoicePack, progress: (Float, Boolean) -> Unit) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (pack.installed(context)) return@withContext
                val root = File(context.filesDir, "voice/models").apply { mkdirs() }
                root.listFiles()?.filter { it.name.startsWith(".download-") }?.forEach { it.deleteRecursively() }
                val staging = File(root, ".download-${UUID.randomUUID()}").apply { mkdir() }
                val archive = File(staging, "pack.tar.bz2")
                var lastPercent = -1
                fun report(fraction: Float, extracting: Boolean) {
                    val bounded = fraction.coerceIn(0f, 1f)
                    val percent = (bounded * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        progress(bounded, extracting)
                    }
                }
                try {
                    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/${pack.release}/${pack.directory}.tar.bz2"
                    val call = client.newCall(Request.Builder().url(url).build())
                    active.set(call)
                    call.execute().use { response ->
                        check(response.isSuccessful)
                        val body = checkNotNull(response.body)
                        val totalBytes = body.contentLength().takeIf { it > 0L } ?: pack.bytes
                        val input = body.byteStream()
                        archive.outputStream().buffered().use { output ->
                            var received = 0L
                            copyVoicePackBytes(input, output, beforeWrite = { _, count ->
                                received += count
                            }, progress = { report(received.toFloat() / totalBytes, false) })
                        }
                    }
                    lastPercent = -1
                    report(0f, true)
                    val extracted = pack.unpack(archive, staging) { report(it, true) }
                    currentCoroutineContext().ensureActive()
                    if (File(context.filesDir, "voice/models/${pack.directory}").exists()) check(File(context.filesDir, "voice/models/${pack.directory}").deleteRecursively())
                    check(extracted.renameTo(File(context.filesDir, "voice/models/${pack.directory}")))
                    progress(1f, true)
                } finally {
                    active.getAndSet(null)?.cancel()
                    staging.deleteRecursively()
                }
            }
        }
}

internal suspend fun copyVoicePackBytes(
    input: InputStream,
    output: OutputStream?,
    beforeWrite: (ByteArray, Int) -> Unit = { _, _ -> },
    progress: () -> Unit,
) = withContext(Dispatchers.IO) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        beforeWrite(buffer, count)
        output?.write(buffer, 0, count)
        progress()
    }
}
