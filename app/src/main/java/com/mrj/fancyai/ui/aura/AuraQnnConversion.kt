package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.SkelExtractor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/** Converts checkpoint-owned text encoders, VAE and UNet with isolated native tools. */
internal class AuraQnnConversion(
    private val app: Context,
    private val onLine: (String) -> Unit,
) {
    suspend fun convert(uri: Uri, name: String, size: Long, loras: List<AuraLora>): File =
        withContext(Dispatchers.IO) {
            SdModel.prepareThenReplace(File(app.filesDir, "sd_models"), name.removeSuffix(".safetensors") + "-QNN") { destination ->
                val work = File(app.noBackupFilesDir, "qnn-convert-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    onLine(app.getString(R.string.aura_qnn_copying))
                    val checkpoint = File(work, "model.safetensors")
                    val job = currentCoroutineContext()
                    copyWithProgress(app.contentResolver.openInputStream(uri)!!, checkpoint, size) { copied, total ->
                        job.ensureActive()
                        if (copied % (64 * 1024 * 1024) == 0L || copied == total) {
                            onLine(app.getString(R.string.aura_qnn_copied, copied / 1_000_000))
                        }
                    }
                    val header = RandomAccessFile(checkpoint, "r").use { file ->
                        val length = java.lang.Long.reverseBytes(file.readLong()).toInt()
                        val bytes = ByteArray(length)
                        file.readFully(bytes)
                        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    }
                    val sdxl = header.keys.any { it.startsWith("conditioner.embedders.") } ||
                        "model.diffusion_model.label_emb.0.0.weight" in header
                    val family = if (sdxl) "template_sdxl" else "template"
                    val componentAssets = "qnn_convert/components_${if (sdxl) "sdxl" else "sd15"}"
                    val libs = File(app.applicationInfo.nativeLibraryDir)
                    convertClip(work, destination, checkpoint, componentAssets, libs)
                    if (sdxl) {
                        installSdxlVae(app, destination) { name, copied, total ->
                            job.ensureActive()
                            if (copied == 0L || copied % (32 * 1024 * 1024) == 0L || copied == total) {
                                onLine(app.getString(R.string.aura_vae_downloading) + " ($name ${copied / 1_000_000}MB / ${total / 1_000_000}MB)")
                            }
                        }
                    }
                    SkelExtractor.extractAll(app)
                    val dsp = File(app.filesDir, "dsp")
                    // Compile one graph at a time; each child exits before its weight pack is removed.
                    val components = if (sdxl) {
                        listOf(
                            Triple("unet", "qnn_convert/$family", R.string.aura_qnn_weights),
                        )
                    } else {
                        listOf(
                            Triple("vae_encoder", "$componentAssets/vae_encoder", R.string.aura_qnn_vae_encoder_weights),
                            Triple("vae_decoder", "$componentAssets/vae_decoder", R.string.aura_qnn_vae_decoder_weights),
                            Triple("unet", "qnn_convert/$family", R.string.aura_qnn_weights),
                        )
                    }
                    for ((component, assetDirectory, weightsStage) in components) {
                        job.ensureActive()
                        compileComponent(component, assetDirectory, weightsStage, work, destination, checkpoint, loras, libs, dsp, sdxl)
                    }
                    if (sdxl) {
                        File(destination, "SDXL").writeText("")
                        File(destination, "qnn_context.txt").writeText("231_masked_v1\n")
                    }
                    job.ensureActive()
                    SdModel.validateImported(destination)
                } finally {
                    if (!work.deleteRecursively()) AppLog.write(Log.WARN, "AuraConvert", "Could not remove conversion work directory")
                }
            }
        }

    private suspend fun convertClip(work: File, destination: File, checkpoint: File, componentAssets: String, libs: File) {
        val clipWork = File(work, "clip").apply { mkdirs() }
        app.assets.open("$componentAssets/clip_recipe.bin").use { input ->
            File(clipWork, "clip_recipe.bin").outputStream().use { input.copyTo(it, 1024 * 1024) }
        }
        onLine(app.getString(R.string.aura_qnn_clips))
        runTool(
            listOf(
                File(libs, "libaura_componentconv.so").path,
                clipWork.path, checkpoint.path, destination.path,
            ),
            work, emptyMap(),
        )
        app.assets.open("$componentAssets/tokenizer.json").use { input ->
            File(destination, "tokenizer.json").outputStream().use { input.copyTo(it, 1024 * 1024) }
        }
    }

    private suspend fun compileComponent(
        component: String,
        assetDirectory: String,
        weightsStage: Int,
        work: File,
        destination: File,
        checkpoint: File,
        loras: List<AuraLora>,
        libs: File,
        dsp: File,
        sdxl: Boolean,
    ) {
        val componentWork = File(work, component).apply { mkdirs() }
        try {
            val template = File(componentWork, "template").apply { mkdirs() }
            for (asset in app.assets.list(assetDirectory).orEmpty()) {
                currentCoroutineContext().ensureActive()
                app.assets.open("$assetDirectory/$asset").use { input ->
                    File(template, asset).outputStream().use { input.copyTo(it, 1024 * 1024) }
                }
            }
            val pack = File(componentWork, "out.pack")
            onLine(app.getString(weightsStage))
            runTool(
                listOf(
                    File(libs, "libaura_tplconv.so").path,
                    File(template, "recipe.bin").path,
                    File(template, "tpl_trim.pack").path,
                    checkpoint.path, pack.path,
                ) + if (component == "unet") {
                    loras.flatMap { listOf("--lora", "${it.file.path}:${it.strength}") }
                } else emptyList(),
                componentWork, emptyMap(),
            )
            val backend = File(componentWork, "backend.json")
            backend.writeText(kotlinx.serialization.json.buildJsonObject {
                put("backend_extensions", kotlinx.serialization.json.buildJsonObject {
                    put("shared_library_path", kotlinx.serialization.json.JsonPrimitive(File(libs, "libQnnHtpNetRunExtensions.so").path))
                    put("config_file_path", kotlinx.serialization.json.JsonPrimitive(File(template, "htp_config.json").path))
                })
            }.toString())
            onLine(app.getString(R.string.aura_qnn_compiling))
            runTool(
                listOf(
                    File(libs, "libqnncontextgen.so").path,
                    "--model", File(template, "libqnn_model.so").path,
                    "--backend", File(libs, "libQnnHtp.so").path,
                    "--output_dir", destination.path, "--binary_file", component,
                    "--config_file", backend.path, "--log_level", "info",
                ),
                componentWork,
                buildMap {
                    put("LD_LIBRARY_PATH", "${libs.path}:/system/lib64:/vendor/lib64:/vendor/lib64/egl")
                    put("ADSP_LIBRARY_PATH", "${dsp.path};/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp")
                    put("QNN_TPL_PACK", pack.path)
                    if (sdxl) {
                        put("LD_PRELOAD", File(libs, "libcompiler_heap.so").path)
                        put("QNN_COMPILER_HEAP_DIR", componentWork.path)
                    }
                },
            )
        } finally {
            if (!componentWork.deleteRecursively()) {
                AppLog.write(Log.WARN, "AuraConvert", "Could not remove component work directory")
            }
        }
    }

    private suspend fun runTool(arguments: List<String>, work: File, environment: Map<String, String>) = coroutineScope {
        val process = ProcessBuilder(arguments).directory(work).redirectErrorStream(true).apply {
            environment().putAll(environment)
        }.start()
        // Cancelling the owner terminates the child before its mapped files are removed.
        val lifetime = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { process.destroyForcibly() }
        }
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    currentCoroutineContext().ensureActive()
                    AppLog.write(Log.INFO, "AuraConvert", line)
                    onLine(line)
                }
            }
            val code = process.waitFor()
            currentCoroutineContext().ensureActive()
            if (code != 0) throw IOException("${File(arguments.first()).name}: exit $code")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                lifetime.cancelAndJoin()
                process.waitFor()
            }
        }
    }
}
