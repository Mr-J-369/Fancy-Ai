package com.mrj.fancyai.ui.gallery

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val FolderCoverShape = RoundedCornerShape(12.dp)

@Composable
internal fun AlbumGrid(albums: List<GalleryAlbum>, onOpen: (GalleryAlbum) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp), modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = GalleryAlbum::id) { AlbumTile(it) { onOpen(it) } }
    }
}

@Composable
internal fun AlbumTile(album: GalleryAlbum, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = album.title }.combinedClickable(onClick = onOpen),
    ) {
        GalleryCover(
            covers = album.covers,
            fallbackPath = album.fallbackPath,
            fallbackResource = album.fallbackResource,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
            album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, end = 2.dp),
        )
        Text(
            pluralStringResource(R.plurals.gallery_images, album.imageCount, album.imageCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp),
        )
    }
}

@Composable
internal fun GalleryCover(
    covers: List<File>,
    fallbackPath: String?,
    @DrawableRes fallbackResource: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.clip(FolderCoverShape).background(Slate).border(1.dp, Hairline, FolderCoverShape)) {
        when {
            covers.size == 1 -> GalleryBitmap(covers[0], Modifier.fillMaxSize())
            covers.size > 1 -> Row(Modifier.fillMaxSize()) {
                GalleryBitmap(covers[0], Modifier.weight(1.65f).fillMaxHeight())
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    GalleryBitmap(covers[1], Modifier.weight(1f).fillMaxWidth())
                    if (covers.size > 2) {
                        Spacer(Modifier.height(2.dp))
                        GalleryBitmap(covers[2], Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            fallbackPath?.let(::File)?.isFile == true -> GalleryBitmap(File(fallbackPath), Modifier.fillMaxSize())
            fallbackResource != 0 -> Image(
                painter = painterResource(fallbackResource),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
            else -> CoverPlaceholder()
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.62f)))),
        )
    }
}

@Composable
internal fun CoverPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(SlateRaised, Slate, Ink))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent.copy(alpha = 0.72f), modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun FolderGrid(
    folders: List<GalleryFolder>,
    images: List<File>,
    selected: Set<String>,
    onFolder: (GalleryFolder) -> Unit,
    onImage: (File) -> Unit,
    onSelect: (File) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 24.dp), modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(folders, key = { "folder:${it.directory.absolutePath}" }) { folder ->
            FolderTile(folder) { onFolder(folder) }
        }
        items(images, key = File::getAbsolutePath) { file ->
            ImageTile(
                file = file,
                selected = file.absolutePath in selected,
                onOpen = { onImage(file) },
            ) { onSelect(file) }
        }
    }
}

@Composable
internal fun FolderTile(folder: GalleryFolder, onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(FolderCoverShape).background(Slate)
            .border(1.dp, Hairline, FolderCoverShape)
            .semantics { contentDescription = folder.directory.name }.combinedClickable(onClick = onOpen),
    ) {
        if (folder.covers.isEmpty()) CoverPlaceholder()
        else GalleryBitmap(folder.covers.first(), Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxWidth().height(44.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.92f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
            Text(
                folder.directory.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.gallery_images, folder.imageCount, folder.imageCount),
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
            )
        }
    }
}

@Composable
internal fun ImageTile(file: File, selected: Boolean, onOpen: () -> Unit, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(Slate)
            .border(if (selected) 2.dp else 0.dp, if (selected) Accent else Color.Transparent, shape)
            .semantics { contentDescription = file.name }
            .combinedClickable(onClick = onOpen, onLongClick = onSelect),
    ) {
        GalleryBitmap(file, Modifier.fillMaxSize())
        if (selected) Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun GalleryBitmap(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
    val cached = remember(key) { synchronized(GalleryThumbnails.cache) { GalleryThumbnails.cache[key] } }
    var bitmap by remember(key) { mutableStateOf(cached) }
    LaunchedEffect(key) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { GalleryThumbnails.load(context, file) }
    }
    Box(modifier.background(Slate), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun EmptyFolder(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.gallery_empty_folder_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.gallery_empty_folder_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
