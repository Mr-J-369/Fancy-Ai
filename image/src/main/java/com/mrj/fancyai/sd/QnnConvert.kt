package com.mrj.fancyai.sd

import android.content.Context
import android.util.Log
import com.mrj.fancyai.util.SkelExtractor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object QnnConvert {
    private const val TAG = "fancyqnnconv"

    @Volatile
    private var activeProcess: Process? = null
    private val cancelled = AtomicBoolean(false)

    fun interface ProgressCallback {
        fun onNativeProgress(percent: Int, message: String)
    }

    fun convert(
        context: Context,
        dir: String,
        safetensorsName: String,
        isSdxl: Boolean,
        clipSkip2: Boolean,
        loraNames: Array<String>,
        loraStrengths: FloatArray,
        callback: ProgressCallback,
    ): String? {
        cancelled.set(false)
        SkelExtractor.extractAll(context)

        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        val compilerExe = File(nativeLibs, "libfancy_qnn_compile.so")
        val compilerHeapLib = File(nativeLibs, "libcompiler_heap.so")
        val skelDir = File(context.filesDir, "dsp").absolutePath
        val heapDir = File(context.noBackupFilesDir, "qnn_heap")
        heapDir.deleteRecursively()
        heapDir.mkdirs()

        val cmd = buildList {
            add(compilerExe.absolutePath)
            add(dir)
            add(safetensorsName)
            add(skelDir)
            add(if (isSdxl) "1" else "0")
            add(if (clipSkip2) "1" else "0")
            for (i in loraNames.indices) {
                val strength = loraStrengths.getOrElse(i) { 1.0f }
                add(loraNames[i])
                add(strength.toString())
            }
        }

        val pb = ProcessBuilder(cmd)
            .directory(File(dir))
            .redirectErrorStream(true)
        pb.environment().apply {
            if (compilerHeapLib.isFile) {
                put("LD_PRELOAD", compilerHeapLib.absolutePath)
                put("QNN_COMPILER_HEAP_DIR", heapDir.absolutePath)
            }
            put("LD_LIBRARY_PATH", "${nativeLibs.absolutePath}:/system/lib64:/vendor/lib64:/vendor/lib64/egl")
            put("ADSP_LIBRARY_PATH", "$skelDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp")
        }

        val process = pb.start()
        activeProcess = process
        var lastPercent = 0
        var explicitError: String? = null
        val tail = ArrayDeque<String>(24)
        return try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("PROGRESS|") -> {
                            val parts = line.split('|', limit = 3)
                            lastPercent = parts.getOrNull(1)?.toIntOrNull() ?: lastPercent
                            val msg = parts.getOrNull(2).orEmpty()
                            callback.onNativeProgress(lastPercent, msg)
                        }
                        line.startsWith("ERROR|") -> {
                            explicitError = line.removePrefix("ERROR|")
                            Log.e(TAG, explicitError.orEmpty())
                        }
                        line == "DONE" -> Unit
                        else -> {
                            Log.i(TAG, line)
                            if (line.isNotBlank()) {
                                callback.onNativeProgress(lastPercent, "LOG|$line")
                            }
                            if (tail.size >= 24) tail.removeFirst()
                            tail.addLast(line)
                        }
                    }
                }
            }
            val rc = process.waitFor()
            when {
                cancelled.get() -> "Conversion cancelled."
                rc == 0 -> null
                !explicitError.isNullOrBlank() -> explicitError
                else -> "QNN compiler process failed (exit=$rc): ${tail.lastOrNull().orEmpty()}"
            }
        } finally {
            process.destroyForcibly()
            activeProcess = null
            heapDir.deleteRecursively()
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeProcess?.destroyForcibly()
    }
}
