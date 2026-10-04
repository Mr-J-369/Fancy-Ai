package com.mrj.fancyai.util

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SkelExtractor {
    private const val TAG = "SkelExtractor"

    fun extractAll(context: Context) {
        val destDir = File(context.filesDir, "dsp")
        val stamp = File(destDir, ".qnn_version")

        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.longVersionCode}.${info.lastUpdateTime}"
        } catch (_: Exception) {
            "0"
        }

        if (destDir.isDirectory && stamp.exists() && (stamp.readText() == version)) return

        try {
            destDir.mkdirs()
            // FastRPC reads these public SDK libraries outside the app UID.
            Os.chmod(destDir.path, OsConstants.S_IRWXU or OsConstants.S_IRGRP or
                OsConstants.S_IXGRP or OsConstants.S_IROTH or OsConstants.S_IXOTH)
            val qnnFiles = (context.assets.list("qnn") ?: emptyArray()).map { "qnn/$it" }
            val ditFiles = (context.assets.list("ditlibs") ?: emptyArray()).map { "ditlibs/$it" }
            for (assetPath in qnnFiles + ditFiles) {
                val filename = assetPath.substringAfterLast('/')
                if (!filename.endsWith(".so")) continue
                val outFile = File(destDir, filename)
                val tmpFile = File(destDir, "$filename.tmp")
                context.assets.open(assetPath).use { input ->
                    tmpFile.delete()
                    FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                }
                outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete()
                    throw IOException("Could not install $filename into ${destDir.path}")
                }
                Os.chmod(outFile.path, OsConstants.S_IRUSR or OsConstants.S_IRGRP or OsConstants.S_IROTH)
            }
            stamp.writeText(version)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract QNN skeletons", e)
        }
    }
}
