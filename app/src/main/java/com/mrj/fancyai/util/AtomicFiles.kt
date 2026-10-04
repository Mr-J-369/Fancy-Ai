package com.mrj.fancyai.util

import android.util.AtomicFile
import java.io.File

internal fun writeAtomicFile(destination: File, bytes: ByteArray) {
    val file = AtomicFile(destination)
    val output = file.startWrite()
    try {
        output.write(bytes)
        file.finishWrite(output)
    } catch (failure: Throwable) {
        file.failWrite(output)
        throw failure
    }
}
