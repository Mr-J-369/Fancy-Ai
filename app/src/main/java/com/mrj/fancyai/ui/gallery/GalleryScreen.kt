package com.mrj.fancyai.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mrj.fancyai.util.MediaActions
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    navController: NavHostController,
    viewModel: GalleryViewModel
) {
    val selectedFolder = viewModel.selectedFolder
    val selectedImage = viewModel.selectedImage

    // Hardware back mirrors the toolbar back: drill out of image → folder → screen.
    BackHandler(enabled = selectedImage != null || selectedFolder != null) {
        if (selectedImage != null) viewModel.deselectImage()
        else viewModel.selectFolder(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedFolder ?: "Gallery", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (selectedImage != null) viewModel.deselectImage()
                        else if (selectedFolder != null) viewModel.selectFolder(null)
                        else navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (selectedImage != null) {
                // Swipe between every image in the current folder, starting from the tapped one.
                val folderImages = viewModel.folders[selectedFolder] ?: listOf(selectedImage)
                ImageDetail(
                    images = folderImages,
                    startIndex = folderImages.indexOf(selectedImage).coerceAtLeast(0),
                    onPageChanged = { viewModel.selectImage(it) },
                    onClose = { viewModel.deselectImage() },
                    onDelete = { viewModel.deleteImage(it) }
                )
            } else if (selectedFolder != null) {
                val folderImages = viewModel.folders[selectedFolder] ?: emptyList()
                GalleryGrid(
                    images = folderImages,
                    onImageSelect = { viewModel.selectImage(it) }
                )
            } else {
                FolderGrid(
                    folders = viewModel.folders,
                    onFolderSelect = { viewModel.selectFolder(it) }
                )
            }
        }
    }
}

@Composable
fun FolderGrid(
    folders: Map<String, List<File>>,
    onFolderSelect: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(folders.keys.toList()) { folderName ->
            val count = folders[folderName]?.size ?: 0
            val previewImage = folders[folderName]?.firstOrNull()
            
            Card(
                onClick = { onFolderSelect(folderName) },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Box {
                    if (previewImage != null) {
                        AsyncImage(
                            model = previewImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(folderName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("$count items", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryGrid(
    images: List<File>,
    onImageSelect: (File) -> Unit
) {
    if (images.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No images in this folder", color = Color.Gray)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(images) { image ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onImageSelect(image) }
                ) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun ImageDetail(
    images: List<File>,
    startIndex: Int,
    onPageChanged: (File) -> Unit,
    onClose: () -> Unit,
    onDelete: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, images.size - 1)
    ) { images.size }
    // True when the visible page is zoomed in — disables horizontal paging so panning works.
    var pageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        pageZoomed = false
        images.getOrNull(pagerState.currentPage)?.let(onPageChanged)
    }
    val current = images.getOrNull(pagerState.currentPage)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                current?.let { img ->
                    scope.launch {
                        val ok = MediaActions.saveFileToGallery(context, img)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) "Saved to gallery" else "Save failed",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }) {
                Icon(Icons.Filled.Star, contentDescription = "Save to gallery", tint = Color.White)
            }
            IconButton(onClick = { current?.let { img -> scope.launch { MediaActions.shareFile(context, img) } } }) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
            }
            IconButton(onClick = { current?.let(onDelete) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !pageZoomed,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            ZoomablePagerImage(
                model = images[page],
                onZoomChanged = { z -> if (page == pagerState.currentPage) pageZoomed = z }
            )
        }

        Text(
            text = current?.name ?: "",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * A pager-friendly zoomable image: double-tap toggles zoom (1x ↔ 2.5x), drag pans while
 * zoomed. It only intercepts drags when zoomed in, so at rest the parent [HorizontalPager]
 * is free to swipe between images. [onZoomChanged] lets the pager disable swiping while zoomed.
 */
@Composable
fun ZoomablePagerImage(
    model: File,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val zoomedIn = scale.value > 1f

    LaunchedEffect(zoomedIn) { onZoomChanged(zoomedIn) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scope.launch {
                        if (scale.value > 1f) {
                            launch { scale.animateTo(1f) }
                            launch { offsetX.animateTo(0f) }
                            launch { offsetY.animateTo(0f) }
                        } else {
                            scale.animateTo(2.5f)
                        }
                    }
                })
            }
            .then(
                // Pan handler only exists while zoomed — otherwise the pager owns horizontal drags.
                if (zoomedIn) Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(offsetX.value + drag.x)
                            offsetY.snapTo(offsetY.value + drag.y)
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = model,
            contentDescription = model.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value
                }
        )
    }
}
