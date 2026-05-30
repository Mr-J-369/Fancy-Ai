package com.mrj.fancyai.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.ui.components.ImageLightbox
import com.mrj.fancyai.ui.components.MarkdownText
import com.mrj.fancyai.util.MediaActions
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    platform: String,
    navController: NavHostController,
    viewModel: SocialViewModel
) {
    val posts by viewModel.posts.collectAsState(initial = emptyList())
    var showCompose by remember { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    lightbox?.let { file ->
        ImageLightbox(
            model = file,
            onDismiss = { lightbox = null },
            onSave = {
                scope.launch {
                    val ok = MediaActions.saveFileToGallery(context, file)
                    android.widget.Toast.makeText(context, if (ok) "Saved" else "Save failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onShare = { scope.launch { MediaActions.shareFile(context, file) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(platform.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { showCompose = true }, enabled = viewModel.characters.isNotEmpty()) {
                            Icon(Icons.Default.Add, contentDescription = "New Post")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            viewModel.statusText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
            if (posts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No posts yet. Tap + to create one.", color = Color.Gray)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(posts, key = { it.id }) { post ->
                        PostCard(post = post, viewModel = viewModel, onImageClick = { lightbox = it })
                        HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }

    if (showCompose) {
        ComposePostDialog(viewModel = viewModel, platform = platform, onDismiss = { showCompose = false })
    }
}

@Composable
fun PostCard(post: SocialPostEntity, viewModel: SocialViewModel, onImageClick: (File) -> Unit) {
    val authorName = viewModel.characterName(post.charId)
    val avatar = viewModel.characterAvatar(post.charId)
    val imageFile = viewModel.resolveImage(post.imageRef)
    val body = post.caption ?: post.text ?: ""

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // Header
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(36.dp).clip(CircleShape), color = MaterialTheme.colorScheme.secondaryContainer) {
                if (avatar != null) {
                    AsyncImage(model = avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(authorName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                post.subreddit?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
            }
            IconButton(onClick = { viewModel.deletePost(post.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = Color.Gray)
            }
        }

        // Rebbit title
        post.title?.let {
            MarkdownText(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }

        // Image
        if (imageFile != null) {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .background(Color.Black)
                    .clickable { onImageClick(imageFile) }
            ) {
                AsyncImage(model = imageFile, contentDescription = "Tap to enlarge", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
            }
        }

        // Body
        if (body.isNotBlank()) {
            MarkdownText(body, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }

        // Comments + reply
        CommentsSection(post = post, viewModel = viewModel)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CommentsSection(post: SocialPostEntity, viewModel: SocialViewModel) {
    val comments by viewModel.comments(post.id).collectAsState(initial = emptyList())
    var replyText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        comments.forEach { c ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text("${c.authorName}: ", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = if (c.authorId == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                MarkdownText(c.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("✕", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { viewModel.deleteComment(c.id) }.padding(start = 6.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                placeholder = { Text("Add a reply…", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = {
                viewModel.addUserReply(post.id, replyText)
                replyText = ""
                keyboardController?.hide()
            }, enabled = replyText.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Reply", tint = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = { viewModel.requestCharacterReply(post) }, enabled = !viewModel.isBusy) {
            Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Character reply", fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposePostDialog(viewModel: SocialViewModel, platform: String, onDismiss: () -> Unit) {
    val characters = viewModel.characters
    var charId by remember { mutableStateOf(characters.firstOrNull()?.id ?: "") }
    var pickerOpen by remember { mutableStateOf(false) }
    var useAi by remember { mutableStateOf(true) }
    var manualText by remember { mutableStateOf("") }
    var withImage by remember { mutableStateOf(platform == "ustagram") }
    val selectedName = characters.firstOrNull { it.id == charId }?.name ?: "Select character"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New ${platform.replaceFirstChar { it.uppercase() }} post") },
        text = {
            Column {
                // Character picker
                Box {
                    OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedName, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        characters.forEach { ch ->
                            DropdownMenuItem(text = { Text(ch.name) }, onClick = { charId = ch.id; pickerOpen = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Switch(checked = useAi, onCheckedChange = { useAi = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate with AI")
                }

                if (!useAi) {
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text("Post text") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Switch(checked = withImage, onCheckedChange = { withImage = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Attach generated image")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.createPost(charId, useAi, manualText, withImage); onDismiss() },
                enabled = charId.isNotBlank() && (useAi || manualText.isNotBlank())
            ) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
