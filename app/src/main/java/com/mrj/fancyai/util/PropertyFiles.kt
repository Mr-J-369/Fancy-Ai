package com.mrj.fancyai.util

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

internal fun writeProperties(file: File, values: Properties) {
    val atomic = AtomicFile(file)
    var stream: FileOutputStream? = null
    try {
        val output = atomic.startWrite().also { stream = it }
        values.store(output, null)
        atomic.finishWrite(output)
    } catch (failure: Throwable) {
        stream?.let(atomic::failWrite)
        throw failure
    }
}
