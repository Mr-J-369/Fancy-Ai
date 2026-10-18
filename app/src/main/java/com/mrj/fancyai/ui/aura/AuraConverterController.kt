package com.mrj.fancyai.ui.aura

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mrj.fancyai.MainActivity
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.service.IImageService
import com.mrj.fancyai.service.InferenceForegroundService
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.ui.settings.ImportDocument
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal class AuraConverterController(val app: Context, private val scope: CoroutineScope) {
    private val preferences = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    var service: IImageService? = null
    var checkpointUri by mutableStateOf<Uri?>(null)
        private set
    var checkpoint by mutableStateOf<ImportDocument?>(null)
        private set
    var loras by mutableStateOf<List<AuraLora>>(emptyList())
        private set
    var operation by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var converting by mutableStateOf(false)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var installedModel by mutableStateOf<File?>(null)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var conversionLog by mutableStateOf<List<String>>(emptyList())
        private set
    var report by mutableStateOf<CheckpointReport?>(null)
        private set
    var inspecting by mutableStateOf(false)
        private set
    var job: Job? = null
        private set

    suspend fun loadLoras() {
        loras = withContext(Dispatchers.IO) {
            File(app.filesDir, LORA_DIRECTORY).apply { mkdirs() }.listFiles()
                ?.filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                ?.sortedBy(File::getName)
                ?.map { AuraLora(it, preferences.getFloat("lora_strength_${it.name}", 1f)) }
                .orEmpty()
        }
    }

    fun chooseCheckpoint(uri: Uri?) {
        uri ?: return
        try {
            val document = documentInfo(app, uri, "model.safetensors")
            checkpoint = document
            checkpointUri = uri
            installedModel = null
            cancelled = false
            error = null
            conversionLog = emptyList()
            report = null
            inspecting = true
            scope.launch(Dispatchers.IO) {
                val inspected = inspectCheckpoint(app.contentResolver, uri)
                if (checkpointUri == uri) {
                    report = inspected
                    inspecting = false
                }
            }
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, "AuraConvert", "Checkpoint document lookup failed", failure)
            error = app.getString(R.string.aura_import_failed)
        }
    }

    private fun preflight(npu: Boolean): String? {
        val document = checkpoint ?: return app.getString(R.string.aura_import_failed)
        val family = report?.family
        val headroom = if (family == CheckpointFamily.SDXL) 6L * 1024 * 1024 * 1024 else 2L * 1024 * 1024 * 1024
        val storage = app.getSystemService(StorageManager::class.java) ?: return null
        val free = runCatching { storage.getAllocatableBytes(storage.getUuidForPath(app.filesDir)) }.getOrNull() ?: return null
        if (free < document.size + headroom) {
            return app.getString(R.string.aura_converter_no_space)
        }
        if (npu && family != null && family != CheckpointFamily.UNKNOWN) {
            val missing = convertAssetsPresent(family)
            if (!missing) return app.getString(R.string.aura_converter_missing_assets)
        }
        return null
    }

    private fun convertAssetsPresent(family: CheckpointFamily): Boolean {
        val template = if (family == CheckpointFamily.SDXL) "template_sdxl" else "template"
        val components = if (family == CheckpointFamily.SDXL) "components_sdxl" else "components_sd15"
        return runCatching {
            app.assets.open("qnn_convert/$template/recipe.bin").close()
            app.assets.open("qnn_convert/$components/clip_recipe.bin").close()
            true
        }.getOrDefault(false)
    }

    private fun notifyFinished(success: Boolean, name: String) {        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = runCatching { app.getSystemService(NotificationManager::class.java) }.getOrNull() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.home_app_aura_converter), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle(app.getString(R.string.home_app_aura_converter))
            .setContentText(
                if (success) app.getString(R.string.aura_converter_done, name)
                else app.getString(R.string.aura_import_failed),
            )
            .setContentIntent(openApp)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun convert(npu: Boolean) {
        val uri = checkpointUri ?: return
        val document = checkpoint ?: return
        preflight(npu)?.let { blocker ->
            error = blocker
            return
        }
        val adapters = loras.filter { it.strength != 0f }
        operation = app.getString(R.string.aura_qnn_copying)
        progress = null
        converting = true
        cancelled = false
        startedAt = SystemClock.elapsedRealtime()
        installedModel = null
        error = null
        conversionLog = emptyList()
        job = scope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    if (npu) {
                        InferenceForegroundService.start(app)
                        LlmEngineClient.sessionMutex.withLock {
                            val session = LlmEngineClient.captureForImage()
                            try {
                                service?.unloadModels()
                                session?.first?.unload()
                                AuraQnnConversion(app) { line ->
                                    scope.launch {
                                        operation = line
                                        conversionLog = (conversionLog + line).takeLast(200)
                                    }
                                }.convert(uri, document.name, document.size, adapters)
                            } finally {
                                withContext(NonCancellable) {
                                    try {
                                        session?.let { (client, config) -> client.restoreAfterImage(config) }
                                    } catch (failure: Exception) {
                                        AppLog.write(Log.ERROR, "AuraConvert", "Text model restoration after conversion failed", failure)
                                    }
                                }
                            }
                        }
                    } else {
                        val context = currentCoroutineContext()
                        SdModel.importSafetensorsAsMnn(
                            ctx = app,
                            input = app.contentResolver.openInputStream(uri)!!,
                            name = document.name,
                            sizeBytes = document.size,
                            loras = adapters.filter { it.strength > 0f }.map { lora ->
                                SdModel.Lora(lora.file.name, lora.strength, lora.file::inputStream)
                            },
                            onStage = { stage ->
                                context.ensureActive()
                                val label = importStageLabel(app, stage, document.name)
                                scope.launch {
                                    operation = label
                                    progress = null
                                    conversionLog = (conversionLog + label).takeLast(200)
                                }
                            },
                            onProgress = { read, total ->
                                context.ensureActive()
                                scope.launch { progress = if (total > 0L) read.toFloat() / total else null }
                            },
                        )
                    }
                }
                preferences.edit {
                    putString(KEY_MODEL, model.path)
                    putString(KEY_AURA_ENGINE, AuraEngine.LOCAL.name)
                }
                installedModel = model
                notifyFinished(true, model.name)
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, "AuraConvert", "Checkpoint conversion failed", failure)
                error = app.getString(R.string.aura_import_failed)
                notifyFinished(false, document.name)
            } finally {
                converting = false
                operation = null
                progress = null
            }
        }
    }

    fun importLora(uri: Uri?) {
        uri ?: return
        operation = app.getString(R.string.aura_copying_lora)
        error = null
        job = scope.launch {
            try {
                val document = documentInfo(app, uri, "lora.safetensors")
                require(document.name.endsWith(".safetensors", ignoreCase = true))
                withContext(Dispatchers.IO) {
                    val root = File(app.filesDir, LORA_DIRECTORY).apply { mkdirs() }
                    val target = File(root, SdModel.sanitize(document.name) + ".safetensors")
                    val source = app.contentResolver.openInputStream(uri)!!
                    copyWithProgress(source, target, document.size) { read, total ->
                        scope.launch { progress = if (total > 0L) read.toFloat() / total else null }
                    }
                    preferences.edit { putFloat("lora_strength_${target.name}", 1f) }
                }
                loadLoras()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, "AuraConvert", "LoRA import failed", failure)
                error = app.getString(R.string.aura_lora_import_failed)
            } finally {
                operation = null
                progress = null
            }
        }
    }

    fun setStrength(lora: AuraLora, value: Float) {
        preferences.edit { putFloat("lora_strength_${lora.file.name}", value) }
        loras = loras.map { if (it.file == lora.file) it.copy(strength = value) else it }
    }

    fun removeLora(lora: AuraLora) {
        if (lora.file.delete()) {
            preferences.edit { remove("lora_strength_${lora.file.name}") }
            loras = loras.filterNot { it.file == lora.file }
        }
    }
}

private const val CHANNEL_ID = "aura_conversion"
private const val NOTIFICATION_ID = 101

