package com.mrj.fancyai.ui.faceswap

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.face.swap.FaceSwapModels
import com.mrj.fancyai.sd.face.swap.FaceSwapPipeline
import com.mrj.fancyai.sd.face.swap.MnnFaceSwap
import com.mrj.fancyai.ui.aura.downloadDirect
import com.mrj.fancyai.ui.gallery.GENERATED_DIRECTORY
import com.mrj.fancyai.ui.gallery.MEDIA_DIRECTORY
import com.mrj.fancyai.util.IMAGE_EXTENSIONS
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val FACESWAP_PREFERENCES = "faceswap"
private const val KEY_CONSENT = "consent_accepted"

/** Which photo slot a picker is filling. */
internal enum class FaceSwapSlot { SOURCE, TARGET }

/** Owns Aura Swap state, model downloads, and swap orchestration. */
internal class FaceSwapController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences(FACESWAP_PREFERENCES, Context.MODE_PRIVATE)
    private val modelsDir =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "faceswap")
            .apply { mkdirs() }
    private val swapMutex = Mutex()

    var sourceBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var targetBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var resultFile by mutableStateOf<File?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var allFaces by mutableStateOf(false)
    var fidelity by mutableFloatStateOf(0.7f)
    var needsConsent by mutableStateOf(!preferences.getBoolean(KEY_CONSENT, false))
        private set
    var modelsInstalled by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var downloading by mutableStateOf(false)
        private set
    var galleryImages by mutableStateOf<List<File>>(emptyList())
        private set

    fun refresh() {
        modelsInstalled = FaceSwapModels.ALL.all { (modelFile(it.fileName).length() > 0L) }
        scope.launch(Dispatchers.IO) {
            val mediaRoot = File(context.filesDir, MEDIA_DIRECTORY)
            val files = mediaRoot.walkTopDown().filter {
                (it.isFile) && (it.extension.lowercase() in IMAGE_EXTENSIONS)
            }.sortedByDescending { it.lastModified() }.take(60).toList()
            withContext(Dispatchers.Main) { galleryImages = files }
        }
    }

    fun acceptConsent() {
        preferences.edit { putBoolean(KEY_CONSENT, true) }
        needsConsent = false
    }

    fun onUriPicked(slot: FaceSwapSlot, uri: Uri?) {
        uri ?: return
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { decodeImage(context, uri, 1024) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    if (slot == FaceSwapSlot.SOURCE) sourceBitmap = bitmap else targetBitmap = bitmap
                    resultFile = null
                    error = null
                } else {
                    error = context.getString(R.string.faceswap_failed)
                }
            }
        }
    }

    fun onAllFaces(value: Boolean) { allFaces = value }
    fun onFidelity(value: Float) { fidelity = value }

    fun pickBitmap(slot: FaceSwapSlot, bitmap: Bitmap) {
        if (slot == FaceSwapSlot.SOURCE) sourceBitmap = bitmap else targetBitmap = bitmap
        resultFile = null
        error = null
    }

    fun installModels() {
        if (downloading) return
        downloading = true
        downloadProgress = 0f
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                val total = FaceSwapModels.ALL.sumOf { it.sizeBytes }.toFloat()
                var done = 0L
                FaceSwapModels.ALL.forEach { model ->
                    val target = modelFile(model.fileName)
                    if (target.length() <= 0L) {
                        downloadDirect(context, model.url, target, model.sizeBytes) { read, _ ->
                            downloadProgress = (done + read) / total
                        }
                    }
                    done += target.length()
                    downloadProgress = done / total
                }
                withContext(Dispatchers.Main) { modelsInstalled = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    error = failure.message ?: context.getString(R.string.faceswap_install_failed)
                }
            } finally {
                withContext(Dispatchers.Main) { downloading = false }
            }
        }
    }

    fun run() {
        val source = sourceBitmap
        val target = targetBitmap
        if (source == null || target == null) {
            error = context.getString(R.string.faceswap_pick_both)
            return
        }
        if (!modelsInstalled) {
            error = context.getString(R.string.faceswap_not_installed)
            return
        }
        if (busy) return
        busy = true
        error = null
        resultFile = null
        scope.launch(Dispatchers.IO) {
            val result = swapMutex.withLock {
                runCatching {
                    val paths = FaceSwapModels.ALL.map { modelFile(it.fileName).absolutePath }
                    check(MnnFaceSwap.nativeLoad(paths[0], paths[1], paths[2], paths[3], false)) {
                        context.getString(R.string.faceswap_failed)
                    }
                    try {
                        val srcPx = IntArray(source.width * source.height)
                        source.getPixels(srcPx, 0, source.width, 0, 0, source.width, source.height)
                        val tgtPx = IntArray(target.width * target.height)
                        target.getPixels(tgtPx, 0, target.width, 0, 0, target.width, target.height)
                        val out = FaceSwapPipeline.swap(
                            srcPx, source.width, source.height,
                            tgtPx, target.width, target.height,
                            FaceSwapPipeline.Options(allFaces = allFaces, fidelity = fidelity),
                        ) ?: error(context.getString(R.string.faceswap_no_face))
                        Bitmap.createBitmap(out, target.width, target.height, Bitmap.Config.ARGB_8888)
                    } finally {
                        MnnFaceSwap.nativeUnload()
                    }
                }
            }
            val saved = result.getOrNull()?.let { saveResult(it) }
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    resultFile = saved
                } else {
                    error = result.exceptionOrNull()?.message ?: context.getString(R.string.faceswap_save_failed)
                }
                busy = false
            }
        }
    }

    private fun modelFile(name: String): File = File(modelsDir, name)

    private fun saveResult(bitmap: Bitmap): File? = runCatching {
        val dir = File(File(context.filesDir, MEDIA_DIRECTORY), GENERATED_DIRECTORY).apply { mkdirs() }
        val target = File(dir, "swap_${System.currentTimeMillis()}.jpg")
        FileOutputStream(target).use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "compress" }
            it.fd.sync()
        }
        target
    }.getOrNull()
}
