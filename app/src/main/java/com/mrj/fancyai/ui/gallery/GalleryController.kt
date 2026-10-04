package com.mrj.fancyai.ui.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.ui.aura.AURA_PREFERENCES
import com.mrj.fancyai.ui.aura.KEY_CFG
import com.mrj.fancyai.ui.aura.KEY_CUSTOM_HEIGHT
import com.mrj.fancyai.ui.aura.KEY_CUSTOM_WIDTH
import com.mrj.fancyai.ui.aura.KEY_MODEL
import com.mrj.fancyai.ui.aura.KEY_NEGATIVE_PROMPT
import com.mrj.fancyai.ui.aura.KEY_PROMPT
import com.mrj.fancyai.ui.aura.KEY_SAMPLER
import com.mrj.fancyai.ui.aura.KEY_SEED
import com.mrj.fancyai.ui.aura.KEY_SEED_LOCKED
import com.mrj.fancyai.ui.aura.KEY_STEPS
import com.mrj.fancyai.ui.aura.installedModels
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.util.IMAGE_EXTENSIONS
import com.mrj.fancyai.util.auraSidecar
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale

internal class GalleryController(private val context: Context) {
    val mediaRoot = File(context.filesDir, MEDIA_DIRECTORY)
    var revision by mutableIntStateOf(0)
    var albums by mutableStateOf<List<GalleryAlbum>>(emptyList())
    var location by mutableStateOf<GalleryLocation?>(null)
    var contents by mutableStateOf(GalleryContents())
    var loading by mutableStateOf(true)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var viewing by mutableStateOf<File?>(null)
    var fullImage by mutableStateOf<Bitmap?>(null)
    var details by mutableStateOf<GalleryDetails?>(null)
    var deleting by mutableStateOf<List<File>>(emptyList())
    var exporting by mutableStateOf<File?>(null)
    var notice by mutableStateOf<String?>(null)

    suspend fun load(generatedTitle: String, importedTitle: String) {
        loading = true
        try {
            val current = location
            if (current == null) {
                albums = withContext(Dispatchers.IO) {
                    galleryAlbums(mediaRoot, availableCharacters(context), generatedTitle, importedTitle)
                }
                contents = GalleryContents(imageCount = albums.sumOf(GalleryAlbum::imageCount))
            } else {
                contents = withContext(Dispatchers.IO) {
                    galleryContents(current.directory, current.foldersVisible)
                }
                val paths = contents.images.mapTo(mutableSetOf(), File::getAbsolutePath)
                selected = selected.intersect(paths)
                viewing = viewing?.takeIf { it.absolutePath in paths }
            }
        } finally {
            loading = false
        }
    }

    fun leaveFolder() {
        val current = location ?: return
        val parent = current.directory.parentFile
        selected = emptySet()
        contents = GalleryContents()
        if (current.directory.canonicalFile != current.root.canonicalFile && parent != null) {
            location = current.copy(directory = parent)
        } else {
            location = null
            revision++
        }
    }
}

internal data class GalleryAlbum(
    val id: String,
    val title: String,
    val directory: File,
    val imageCount: Int,
    val covers: List<File>,
    val fallbackPath: String? = null,
    @param:DrawableRes val fallbackResource: Int = 0,
    val foldersVisible: Boolean = true,
)

internal data class GalleryLocation(
    val title: String,
    val root: File,
    val directory: File,
    val foldersVisible: Boolean,
)

internal data class GalleryFolder(val directory: File, val imageCount: Int, val covers: List<File>)
internal data class GalleryContents(
    val folders: List<GalleryFolder> = emptyList(),
    val images: List<File> = emptyList(),
    val imageCount: Int = 0,
)

internal data class GalleryDetails(
    val file: File,
    val width: Int,
    val height: Int,
    val prompt: String,
    val negativePrompt: String,
    val model: String,
    val sampler: String,
    val steps: Int?,
    val cfg: Double?,
    val seed: Long?,
)

internal fun galleryAlbums(
    mediaRoot: File,
    characters: List<CharacterCard>,
    generatedTitle: String,
    importedTitle: String,
): List<GalleryAlbum> {
    val generated = File(mediaRoot, GENERATED_DIRECTORY)
    val characterRoot = File(mediaRoot, CHARACTER_DIRECTORY)
    val characterAlbums = characters.map { character ->
        File(characterRoot, character.id).toAlbum(
            id = "character:${character.id}",
            title = character.name,
            fallbackPath = character.avatarPath,
            fallbackResource = character.avatarResource,
        )
    }
    val knownIds = characters.mapTo(mutableSetOf(), CharacterCard::id)
    val orphanAlbums = characterRoot.listFiles().orEmpty().asSequence()
        .filter(File::isDirectory).filterNot { it.name in knownIds }
        .map { it.toAlbum("orphan:${it.name}", it.name) }.filter { it.imageCount > 0 }
        .sortedBy { it.title.lowercase(Locale.ROOT) }.toList()
    val imported = galleryImages(mediaRoot).takeIf(List<File>::isNotEmpty)?.let { images ->
        GalleryAlbum("imported", importedTitle, mediaRoot, images.size, images.take(COVER_LIMIT), foldersVisible = false)
    }
    val reserved = setOf(GENERATED_DIRECTORY.lowercase(Locale.ROOT), CHARACTER_DIRECTORY.lowercase(Locale.ROOT))
    val others = mediaRoot.listFiles().orEmpty().asSequence()
        .filter(File::isDirectory).filterNot { it.name.lowercase(Locale.ROOT) in reserved }
        .map { it.toAlbum("folder:${it.name}", it.name) }
        .sortedBy { it.title.lowercase(Locale.ROOT) }.toList()
    return buildList {
        add(generated.toAlbum("generated", generatedTitle))
        addAll(characterAlbums)
        imported?.let(::add)
        addAll(orphanAlbums)
        addAll(others)
    }
}

private fun galleryImages(directory: File): List<File> =
    directory.listFiles().orEmpty().asSequence()
    .filter(File::isFile).filterNot { it.name.startsWith('.') }
    .filter { it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
    .sortedWith(IMAGE_ORDER).toList()

internal fun deleteGalleryImages(images: List<File>) {
    images.forEach { image ->
        image.delete()
        if (image.name.startsWith("aura-")) File(image.parentFile, "${image.nameWithoutExtension}.json").delete()
    }
}

internal fun galleryContents(directory: File, foldersVisible: Boolean): GalleryContents {
    val folders = if (foldersVisible) directory.listFiles().orEmpty().asSequence()
        .filter(File::isDirectory).filterNot { it.name.startsWith('.') }
        .map {
            val images = recursiveImages(it)
            GalleryFolder(it, images.size, images.take(COVER_LIMIT))
        }
        .sortedBy { it.directory.name.lowercase(Locale.ROOT) }.toList() else emptyList()
    val images = galleryImages(directory)
    return GalleryContents(
        folders = folders,
        images = images,
        imageCount = images.size + folders.sumOf(GalleryFolder::imageCount),
    )
}

private fun File.toAlbum(
    id: String,
    title: String,
    fallbackPath: String? = null,
    @DrawableRes fallbackResource: Int = 0,
): GalleryAlbum {
    val images = recursiveImages(this)
    return GalleryAlbum(id, title, this, images.size, images.take(COVER_LIMIT), fallbackPath, fallbackResource)
}

private fun recursiveImages(directory: File): List<File> {
    val result = mutableListOf<File>()
    val pending = ArrayDeque<File>().apply { add(directory) }
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        current.listFiles().orEmpty().forEach { file ->
            when {
                file.name.startsWith('.') -> Unit
                file.isDirectory -> pending.add(file)
                file.isFile && (file.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS) -> result += file
            }
        }
    }
    return result.sortedWith(IMAGE_ORDER)
}

internal fun readDetails(file: File): GalleryDetails {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val metadata = auraSidecar(file)?.let { sidecar -> Json.parseToJsonElement(sidecar.readText()).jsonObject }
    return GalleryDetails(
        file, bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0),
        ((metadata?.get("prompt") as? JsonPrimitive)?.contentOrNull ?: ""), ((metadata?.get("negative") as? JsonPrimitive)?.contentOrNull ?: ""),
        ((metadata?.get("model") as? JsonPrimitive)?.contentOrNull ?: ""), ((metadata?.get("sampler") as? JsonPrimitive)?.contentOrNull ?: ""),
        (metadata?.get("steps") as? JsonPrimitive)?.intOrNull,
        (metadata?.get("cfg") as? JsonPrimitive)?.doubleOrNull,
        (metadata?.get("seed") as? JsonPrimitive)?.longOrNull,
    )
}

internal fun applyGalleryDetailsToAura(context: Context, details: GalleryDetails) {
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val installed = runCatching { installedModels(context) }.getOrDefault(emptyList())
    val currentPath = preferences.getString(KEY_MODEL, null)
    val current = installed.firstOrNull { it.file.path == currentPath } ?: installed.firstOrNull()
    val target = details.model.takeIf(String::isNotBlank)?.let { name ->
        installed.firstOrNull { it.file.name == name }
    } ?: current
    val targetName = target?.file?.name
    preferences.edit {
        if (details.prompt.isNotBlank()) putString(KEY_PROMPT, details.prompt)
        if (details.negativePrompt.isNotBlank()) putString(KEY_NEGATIVE_PROMPT, details.negativePrompt)
        target?.let { putString(KEY_MODEL, it.file.path) }
        if (targetName != null) {
            details.steps?.let { putString("model.$targetName.$KEY_STEPS", it.toString()) }
            details.cfg?.let { putString("model.$targetName.$KEY_CFG", it.toFloat().toString()) }
            if (details.sampler in SamplerType.entries.map { it.name }) {
                putString("model.$targetName.$KEY_SAMPLER", details.sampler)
            }
            details.seed?.let {
                putString("model.$targetName.$KEY_SEED", it.toString())
                putBoolean("model.$targetName.$KEY_SEED_LOCKED", true)
            }
            if (details.width > 0) putString("model.$targetName.$KEY_CUSTOM_WIDTH", details.width.toString())
            if (details.height > 0) putString("model.$targetName.$KEY_CUSTOM_HEIGHT", details.height.toString())
        }
    }
}

internal fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/*")
    send.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
    send.putExtra(Intent.EXTRA_STREAM, uri)
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.gallery_share_image)))
}

internal object GalleryThumbnails {
    val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1_024
    }
    fun load(context: Context, file: File): Bitmap? {
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        synchronized(cache) { cache[key] }?.let { return it }
        return runCatching { decodeImage(context, Uri.fromFile(file), THUMBNAIL_EDGE) }.getOrNull()?.also { synchronized(cache) { cache.put(key, it) } }
    }
}

internal const val MEDIA_DIRECTORY = "media"
internal const val GENERATED_DIRECTORY = "Generated"
private const val CHARACTER_DIRECTORY = "characters"
private const val COVER_LIMIT = 3
private const val THUMBNAIL_EDGE = 512
internal const val VIEW_EDGE = 4_096
private const val THUMBNAIL_CACHE_KB = 32 * 1_024
private val IMAGE_ORDER = compareByDescending(File::lastModified)
    .thenBy(String.CASE_INSENSITIVE_ORDER, File::getName)
    .thenBy(File::getName)
