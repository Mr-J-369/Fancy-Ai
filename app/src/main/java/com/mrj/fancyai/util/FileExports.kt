package com.mrj.fancyai.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

internal inline fun exportDocument(context: Context, destination: Uri, write: (OutputStream) -> Unit) {
    try {
        context.contentResolver.openOutputStream(destination, "wt")?.use(write) ?: error("The selected destination could not be opened.")
    } catch (failure: Throwable) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
        throw failure
    }
}
