package com.mrj.fancyai.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.GgufModels
import com.mrj.fancyai.engine.LiteRtModels
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.IMAGE_EXTENSIONS
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

internal sealed interface FileNotice {
    data class Text(@param:StringRes val resource: Int) : FileNotice
    data class Count(@param:PluralsRes val resource: Int, val count: Int) : FileNotice
}

internal data class FileManagerEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val depth: Int,
    val empty: Boolean = false,
)

internal enum class FileSort { NAME, DATE, SIZE }

internal data class TextPreview(
    val name: String,
    val content: String,
    val truncated: Boolean,
)

internal data class FileDetails(
    val entry: FileManagerEntry,
    val created: Long,
    val writable: Boolean,
)

internal class FileManagerController(private val context: Context) {
    private val models = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    private val languageModels = File(models, "litert")
    private val llamaModels = File(models, "llama")
    private val media = File(context.filesDir, "media")

    var entries by mutableStateOf(emptyList<FileManagerEntry>())
        private set
    var loading by mutableStateOf(true)
        private set
    var actions by mutableStateOf<FileManagerEntry?>(null)
    var deleteCandidate by mutableStateOf<FileManagerEntry?>(null)
    var details by mutableStateOf<FileDetails?>(null)
    var textPreview by mutableStateOf<TextPreview?>(null)
    var imagePreview by mutableStateOf<Bitmap?>(null)
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<FileNotice?>(null)
    var revision by mutableIntStateOf(0)

    suspend fun loadEntries(
        initialDirectory: File?,
        expanded: Set<String>,
        sort: FileSort,
        ascending: Boolean,
    ) {
        loading = true
        try {
            entries = withContext(Dispatchers.IO) {
                visibleFileEntries(roots(initialDirectory), expanded, sort, ascending)
            }
        } finally {
            loading = false
        }
    }

    fun open(entry: FileManagerEntry, scope: CoroutineScope) {
        scope.launch {
            busy = true
            try {
                when {
                    File(entry.name).extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS -> {
                        imagePreview = withContext(Dispatchers.IO) {
                            decodeImage(context, Uri.fromFile(File(entry.path)), MAX_IMAGE_SIDE)
                        }
                    }
                    File(entry.name).extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS -> {
                        textPreview = withContext(Dispatchers.IO) {
                            val file = File(entry.path)
                            file.inputStream().use { input ->
                                val bytes = ByteArray(MAX_TEXT_BYTES + 1)
                                var count = 0
                                while (count < bytes.size) {
                                    val read = input.read(bytes, count, bytes.size - count)
                                    if (read <= 0) break
                                    count += read
                                }
                                val truncated = count > MAX_TEXT_BYTES
                                val length = minOf(count, MAX_TEXT_BYTES)
                                TextPreview(file.name, String(bytes, 0, length, Charsets.UTF_8), truncated)
                            }
                        }
                    }
                    else -> actions = entry
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = FileNotice.Text(R.string.files_operation_failed)
            } finally {
                busy = false
            }
        }
    }

    fun importFiles(targetPath: String, uris: List<Uri>, scope: CoroutineScope) {
        scope.launch {
            busy = true
            try {
                val count = withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        val name = documentInfo(context, uri, File(uri.lastPathSegment.orEmpty()).name).name
                        val target = when (targetPath) {
                            ROOT_IMPORT_TARGET -> when {
                                name.endsWith(".litertlm", ignoreCase = true) -> languageModels
                                name.endsWith(".gguf", ignoreCase = true) -> llamaModels
                                else -> media
                            }
                            else -> File(targetPath)
                        }
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            when (target.canonicalFile) {
                                languageModels.canonicalFile -> LiteRtModels(languageModels).import(name, input)
                                llamaModels.canonicalFile -> GgufModels(llamaModels).import(name, input)
                                else -> {
                                    val filename = File(name).name
                                    target.mkdirs()
                                    val destination = File(target, filename)
                                    val temporary = File(target, ".${UUID.randomUUID()}.importing")
                                    try {
                                        Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                    } finally {
                                        temporary.delete()
                                    }
                                }
                            }
                        } ?: throw IOException()
                    }
                    uris.size
                }
                notice = FileNotice.Count(R.plurals.files_imported, count)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = FileNotice.Text(R.string.files_import_failed)
            } finally {
                busy = false
                revision++
            }
        }
    }

    fun exportFile(sourcePath: String, uri: Uri, scope: CoroutineScope) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val source = File(sourcePath)
                    exportDocument(context, uri) { output -> source.inputStream().use { it.copyTo(output) } }
                }
                notice = FileNotice.Text(R.string.files_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = FileNotice.Text(R.string.files_export_failed)
            } finally {
                busy = false
            }
        }
    }

    fun loadDetails(entry: FileManagerEntry, scope: CoroutineScope) {
        scope.launch {
            try {
                details = withContext(Dispatchers.IO) {
                    val file = File(entry.path)
                    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    FileDetails(entry, attributes.creationTime().toMillis(), file.canWrite())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = FileNotice.Text(R.string.files_operation_failed)
            }
        }
    }

    fun delete(entry: FileManagerEntry, scope: CoroutineScope) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val file = File(entry.path)
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = FileNotice.Text(R.string.files_operation_failed)
            } finally {
                busy = false
                revision++
            }
        }
    }

    fun roots(initialDirectory: File?): List<File> {
        if (initialDirectory != null) return listOf(initialDirectory)
        val hidden = setOf("character_draft", "dsp", "user_draft")
        val internal = (context.filesDir.listFiles() ?: throw IOException())
            .filterNot { it.name in hidden }
            .toMutableList()
        models.mkdirs()
        val internalRoot = context.filesDir.canonicalFile
        if (!models.canonicalFile.path.startsWith("${internalRoot.path}${File.separator}")) internal += models
        return internal.distinctBy { it.canonicalPath }
    }


}

internal fun visibleFileEntries(
    roots: List<File>,
    expanded: Set<String>,
    sort: FileSort,
    ascending: Boolean,
): List<FileManagerEntry> = buildList {
    fun append(file: File, depth: Int) {
        val entry = FileManagerEntry(
            path = file.absolutePath,
            name = file.name,
            directory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            modified = file.lastModified(),
            depth = depth,
        )
        add(entry)
        if (entry.directory && (entry.path in expanded) && !Files.isSymbolicLink(file.toPath())) {
            val children = sortedFiles(
                file.listFiles()?.toList() ?: throw IOException(),
                sort,
                ascending,
            )
            if (children.isEmpty()) {
                add(entry.copy(path = "${entry.path}#empty", name = "", depth = depth + 1, empty = true))
            }
            else children.forEach { append(it, depth + 1) }
        }
    }
    sortedFiles(roots, sort, ascending).forEach { append(it, 0) }
}

private fun sortedFiles(files: List<File>, sort: FileSort, ascending: Boolean): List<File> = files.sortedWith { left, right ->
    val directoryOrder = compareValues(!left.isDirectory, !right.isDirectory)
    if (directoryOrder != 0) directoryOrder else {
        val compared = when (sort) {
            FileSort.NAME -> left.name.lowercase(Locale.ROOT).compareTo(right.name.lowercase(Locale.ROOT))
            FileSort.DATE -> left.lastModified().compareTo(right.lastModified())
            FileSort.SIZE -> left.length().compareTo(right.length())
        }
        val directed = if (ascending) compared else -compared
        if (directed != 0) directed else left.name.compareTo(right.name, ignoreCase = true)
    }
}

internal const val ROOT_IMPORT_TARGET = "::root::"
private const val MAX_TEXT_BYTES = 512 * 1024
private const val MAX_IMAGE_SIDE = 4_096
internal val TEXT_EXTENSIONS = setOf("csv", "json", "log", "md", "properties", "txt", "xml", "yaml", "yml")
internal val PREVIEW_EXTENSIONS = IMAGE_EXTENSIONS + TEXT_EXTENSIONS
internal val MODEL_EXTENSIONS = setOf("bin", "gguf", "litertlm", "mnn", "onnx", "safetensors")
internal val AUDIO_EXTENSIONS = setOf("flac", "mp3", "ogg", "wav")
internal fun FileManagerEntry.mark(): Int = when {
    directory -> R.drawable.ic_folder
    File(name).extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS -> R.drawable.ic_image
    File(name).extension.lowercase(Locale.ROOT) in MODEL_EXTENSIONS -> R.drawable.ic_model
    File(name).extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS -> R.drawable.ic_audio
    else -> R.drawable.ic_file
}
