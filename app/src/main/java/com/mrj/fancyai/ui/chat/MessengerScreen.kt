package com.mrj.fancyai.ui.chat

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.MemoryEntity
import com.mrj.fancyai.data.db.entity.MessageEntity
import com.mrj.fancyai.data.repository.InboxItem
import com.mrj.fancyai.ui.components.ImageLightbox
import com.mrj.fancyai.ui.components.MarkdownText
import com.mrj.fancyai.ui.theme.FancyAITheme
import com.mrj.fancyai.util.MediaActions
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class MessengerView {
    INBOX, CHAT, PROFILE
}

@Composable
fun MessengerScreen(
    navController: NavHostController,
    viewModel: MessengerViewModel
) {
    var currentView by remember { mutableStateOf(MessengerView.INBOX) }
    val activeChar by viewModel.activeCharacter.collectAsState()

    // Multi-select state for the chat message list (hoisted so the top bar can drive it).
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun exitSelection() { selectionMode = false; selectedIds.clear() }

    // Selection is per-chat; drop it whenever we leave the chat view.
    LaunchedEffect(currentView) { if (currentView != MessengerView.CHAT) exitSelection() }

    // Hardware back: exit selection first, otherwise mirror the toolbar back.
    BackHandler(enabled = currentView != MessengerView.INBOX || selectionMode) {
        if (selectionMode) {
            exitSelection()
        } else {
            currentView = when (currentView) {
                MessengerView.PROFILE -> MessengerView.CHAT
                else -> MessengerView.INBOX
            }
        }
    }

    val avatarFile = activeChar?.avatarRef?.let { viewModel.resolveImageRef(it) }

    Scaffold(
        topBar = {
            MessengerTopBar(
                currentView = currentView,
                activeChar = activeChar,
                avatarFile = avatarFile,
                tokensPerSec = viewModel.tokensPerSec,
                isGenerating = viewModel.isLoading,
                selectionMode = selectionMode,
                selectedCount = selectedIds.size,
                onBack = {
                    when (currentView) {
                        MessengerView.INBOX -> navController.popBackStack()
                        MessengerView.CHAT -> currentView = MessengerView.INBOX
                        MessengerView.PROFILE -> currentView = MessengerView.CHAT
                    }
                },
                onProfileClick = { currentView = MessengerView.PROFILE },
                onStartSelection = { selectionMode = true; selectedIds.clear() },
                onExitSelection = { exitSelection() },
                onDeleteSelected = {
                    viewModel.deleteMessages(selectedIds.toSet())
                    exitSelection()
                },
                onClearChat = { showClearConfirm = true }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            AnimatedContent(targetState = currentView, label = "messenger_transition") { view ->
                when (view) {
                    MessengerView.INBOX -> InboxView(viewModel) { charId ->
                        viewModel.loadConversation(charId)
                        currentView = MessengerView.CHAT
                    }
                    MessengerView.CHAT -> ChatView(
                        viewModel = viewModel,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onToggleSelect = { id ->
                            if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
                        },
                        onStartSelection = { id -> selectionMode = true; selectedIds.clear(); selectedIds.add(id) }
                    )
                    MessengerView.PROFILE -> ProfileView(viewModel) {
                        currentView = MessengerView.CHAT
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        val charId = activeChar?.id
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear chat?") },
            text = { Text("This deletes all messages with ${activeChar?.name ?: "this character"}. The character is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    charId?.let { viewModel.clearChat(it) }
                    showClearConfirm = false
                }) { Text("Clear", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerTopBar(
    currentView: MessengerView,
    activeChar: CharacterEntity?,
    avatarFile: File?,
    tokensPerSec: Float?,
    isGenerating: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    onBack: () -> Unit,
    onProfileClick: () -> Unit,
    onStartSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearChat: () -> Unit
) {
    // Contextual action bar while multi-selecting messages.
    if (currentView == MessengerView.CHAT && selectionMode) {
        TopAppBar(
            modifier = Modifier.statusBarsPadding(),
            title = { Text(if (selectedCount > 0) "$selectedCount selected" else "Select messages") },
            navigationIcon = {
                IconButton(onClick = onExitSelection) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
            },
            actions = {
                IconButton(onClick = onDeleteSelected, enabled = selectedCount > 0) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                }
            }
        )
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.statusBarsPadding(),
        title = {
            when (currentView) {
                MessengerView.INBOX -> Text("Messenger")
                MessengerView.CHAT -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onProfileClick() }
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.tertiary
                        ) {
                            if (avatarFile != null) {
                                AsyncImage(
                                    model = avatarFile,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(4.dp), tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(activeChar?.name ?: "Chat", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            // Token/sec readout: live while generating, last run's rate when idle.
                            val rate = tokensPerSec
                            if (rate != null && rate > 0f) {
                                Text(
                                    text = "${"%.1f".format(rate)} tok/s",
                                    fontSize = 10.sp,
                                    color = if (isGenerating) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }
                        }
                    }
                }
                MessengerView.PROFILE -> Text("Profile")
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (currentView == MessengerView.CHAT) {
                // The "!" options button: toggles an in-chat options menu.
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Chat options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("View profile") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        onClick = { menuOpen = false; onProfileClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Select messages") },
                        leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = { menuOpen = false; onStartSelection() }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear chat") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onClearChat() }
                    )
                }
            }
        }
    )
}

@Composable
fun InboxView(viewModel: MessengerViewModel, onChatClick: (String) -> Unit) {
    val items by viewModel.inboxItems.collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }

    val characterPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val fileName = it.lastPathSegment ?: "character.json"
            viewModel.importCharacter(context.contentResolver.openInputStream(it)!!, fileName, context)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Chats", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New", fontSize = 12.sp)
                }
                TextButton(onClick = { characterPicker.launch("*/*") }) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", fontSize = 12.sp)
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items) { item ->
                InboxRow(
                    item = item,
                    avatarFile = item.character.avatarRef?.let { viewModel.resolveImageRef(it) },
                    onClick = { onChatClick(item.character.id) },
                    onLongPress = { pendingDelete = item.character }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
            }
        }
    }

    if (showCreate) {
        CharacterEditorDialog(
            onDismiss = { showCreate = false },
            onSave = { name, handle, bio, persona ->
                viewModel.createCharacter(name, handle, bio, persona)
                showCreate = false
            }
        )
    }

    pendingDelete?.let { ch ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${ch.name}?") },
            text = { Text("This permanently removes the character and its chat history.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCharacter(ch.id); pendingDelete = null }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** Create-character form (name / handle / bio / persona). */
@Composable
fun CharacterEditorDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, handle: String, bio: String, persona: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Character") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = handle, onValueChange = { handle = it },
                    label = { Text("Handle (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = bio, onValueChange = { bio = it },
                    label = { Text("Bio / background") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = persona, onValueChange = { persona = it },
                    label = { Text("Persona (character card)") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, handle, bio, persona) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxRow(item: InboxItem, avatarFile: File?, onClick: () -> Unit, onLongPress: () -> Unit) {
    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            if (avatarFile != null) {
                AsyncImage(
                    model = avatarFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.character.name, fontWeight = FontWeight.Bold)
                item.lastMessage?.let {
                    Text(
                        text = sdf.format(Date(it.timestamp)),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            Text(
                text = item.lastMessage?.text ?: "No messages yet",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatView(
    viewModel: MessengerViewModel,
    selectionMode: Boolean,
    selectedIds: List<String>,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val streamingText = viewModel.streamingText
    val isLoading = viewModel.isLoading
    var inputText by remember { mutableStateOf("") }
    var lightboxFile by remember { mutableStateOf<File?>(null) }
    var attachedImage by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> attachedImage = uri }

    // Auto-scroll to the newest content (index 0 in this reverse-layout list) as messages
    // arrive and while a reply streams in, so generation stays in view without manual scroll.
    LaunchedEffect(messages.size, streamingText, viewModel.photoGenerating) {
        listState.animateScrollToItem(0)
    }

    lightboxFile?.let { file ->
        ImageLightbox(
            model = file,
            onDismiss = { lightboxFile = null },
            onSave = {
                scope.launch {
                    val ok = MediaActions.saveFileToGallery(context, file)
                    android.widget.Toast.makeText(context, if (ok) "Saved to gallery" else "Save failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onShare = { scope.launch { MediaActions.shareFile(context, file) } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true
        ) {
            if (viewModel.photoGenerating) {
                item { ChatBubble(text = "⏳ Sending photo…", isUser = false) }
            }
            if (streamingText.isNotEmpty()) {
                item {
                    ChatBubble(text = streamingText, isUser = false)
                }
            }
            items(messages, key = { it.id }) { msg ->
                MessageRow(
                    msg = msg,
                    resolveImage = { viewModel.resolveImageRef(it) },
                    onImageClick = { lightboxFile = it },
                    onDelete = { viewModel.deleteMessage(msg) },
                    onRegenerate = { viewModel.regenerate(msg) },
                    selectionMode = selectionMode,
                    selected = selectedIds.contains(msg.id),
                    onToggleSelect = { onToggleSelect(msg.id) },
                    onStartSelection = { onStartSelection(msg.id) }
                )
            }
        }
        
        val send = {
            if (!isLoading) {
                val img = attachedImage
                if (img != null) {
                    viewModel.sendMessageWithImage(inputText.trim(), img, context)
                    attachedImage = null
                    inputText = ""
                    keyboardController?.hide()
                } else if (inputText.isNotBlank()) {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                    keyboardController?.hide()
                }
            }
        }

        // Pending-attachment preview with a remove button.
        attachedImage?.let { uri ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Attachment",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.weight(1f))
                IconButton(onClick = { attachedImage = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove attachment", modifier = Modifier.size(18.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach image — only for vision-capable (cloud) providers.
            if (viewModel.canAttachImages) {
                IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !isLoading) {
                    Icon(Icons.Default.Add, contentDescription = "Attach image", tint = MaterialTheme.colorScheme.primary)
                }
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message...") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = send,
                enabled = !isLoading && (inputText.isNotBlank() || attachedImage != null)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ChatBubble(text: String, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
        ) {
            MarkdownText(
                text = text,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A chat message. Long-press opens a menu (Copy / Regenerate / Select / Delete). In selection
 * mode, tapping toggles the message's checkbox instead. Tapping an image opens the lightbox.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    msg: MessageEntity,
    resolveImage: (String) -> File?,
    onImageClick: (File) -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onStartSelection: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isImage = msg.type == "image"
    val imageFile = if (isImage) resolveImage(msg.text) else null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                else Modifier
            )
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        isImage && imageFile != null -> onImageClick(imageFile)
                    }
                },
                onLongClick = { if (!selectionMode) menuOpen = true }
            )
    ) {
        if (isImage) {
            ImageBubble(file = imageFile, isUser = msg.sender == "user")
        } else {
            ChatBubble(text = msg.text, isUser = msg.sender == "user")
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Select") },
                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { onStartSelection(); menuOpen = false }
            )
            if (!isImage) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboard.setText(AnnotatedString(msg.text))
                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        menuOpen = false
                    }
                )
            }
            if (msg.sender == "ai") {
                DropdownMenuItem(
                    text = { Text("Regenerate") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = { onRegenerate(); menuOpen = false }
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { onDelete(); menuOpen = false }
            )
        }
    }
}

@Composable
fun ImageBubble(file: File?, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = "Tap to enlarge",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Text(
                    "🖼️ image unavailable",
                    modifier = Modifier.padding(12.dp),
                    color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ProfileView(viewModel: MessengerViewModel, onSave: () -> Unit) {
    val char = viewModel.activeCharacter.collectAsState().value ?: return
    val memories by viewModel.activeMemories.collectAsState()
    val dossier by viewModel.activeDossier.collectAsState()
    val context = LocalContext.current

    // Surface the one-shot evolve result (success / skipped / error) as a toast.
    LaunchedEffect(viewModel.evolveMessage) {
        viewModel.evolveMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearEvolveMessage()
        }
    }

    // Keyed on char.id so the editable fields reset when a different character is opened.
    var name by remember(char.id) { mutableStateOf(char.name) }
    var handle by remember(char.id) { mutableStateOf(char.handle) }
    var bio by remember(char.id) { mutableStateOf(char.bio) }
    var persona by remember(char.id) { mutableStateOf(char.persona) }
    var tabIndex by remember { mutableStateOf(0) }

    val avatarFile = char.avatarRef?.let { viewModel.resolveImageRef(it) }
    // The evolved dossier's "relationship" tier, if any, drives the status badge.
    val relationship = parseDossier(dossier?.dossierJson)
        .firstOrNull { it.category == "Relationship" }?.text

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Identity") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Memory Vault") })
        }

        when (tabIndex) {
            0 -> IdentityTab(
                name = name,
                onNameChange = { name = it },
                handle = handle,
                onHandleChange = { handle = it },
                bio = bio,
                onBioChange = { bio = it },
                persona = persona,
                onPersonaChange = { persona = it },
                avatarFile = avatarFile,
                avatarGenerating = viewModel.avatarGenerating,
                onGenerateAvatar = { viewModel.generateAvatar() },
                relationship = relationship,
                autoPostUstagram = char.autoPostUstagram,
                autoPostRebbit = char.autoPostRebbit,
                autoPostY = char.autoPostY,
                nsfwEnabled = viewModel.nsfwEnabled,
                onToggleAutoPost = { platform, enabled -> viewModel.toggleAutoPost(platform, enabled) },
                onSave = {
                    viewModel.saveCharacter(char.copy(name = name, handle = handle, bio = bio, persona = persona))
                    onSave()
                },
                onEvolve = { viewModel.evolveActiveCharacterDossier() },
                evolving = viewModel.dossierEvolving
            )
            1 -> MemoryVaultTab(
                memories = memories,
                dossierFacts = parseDossier(dossier?.dossierJson),
                onDelete = { viewModel.deleteMemory(it) },
                onAdd = { viewModel.addManualMemory(it) }
            )
        }
    }
}

@Composable
fun IdentityTab(
    name: String,
    onNameChange: (String) -> Unit,
    handle: String,
    onHandleChange: (String) -> Unit,
    bio: String,
    onBioChange: (String) -> Unit,
    persona: String,
    onPersonaChange: (String) -> Unit,
    avatarFile: File?,
    avatarGenerating: Boolean,
    onGenerateAvatar: () -> Unit,
    relationship: String?,
    autoPostUstagram: Boolean,
    autoPostRebbit: Boolean,
    autoPostY: Boolean,
    nsfwEnabled: Boolean,
    onToggleAutoPost: (platform: String, enabled: Boolean) -> Unit,
    onSave: () -> Unit,
    onEvolve: () -> Unit,
    evolving: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (avatarFile != null) {
                    AsyncImage(
                        model = avatarFile,
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (avatarGenerating) {
                CircularProgressIndicator()
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onGenerateAvatar, enabled = !avatarGenerating) {
            Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (avatarGenerating) "Generating…" else "Generate Avatar")
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = handle,
            onValueChange = onHandleChange,
            label = { Text("Handle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = bio,
            onValueChange = onBioChange,
            label = { Text("Bio / background") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = persona,
            onValueChange = onPersonaChange,
            label = { Text("Persona") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Profile")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onEvolve,
                enabled = !evolving,
                modifier = Modifier.weight(1f)
            ) {
                if (evolving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (evolving) "Evolving…" else "Evolve AI")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Auto-post opt-in: which social apps this character may autonomously post to.
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Auto-post on",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AutoPostChip("Ustagram", autoPostUstagram) { onToggleAutoPost("ustagram", it) }
                // Rebbit is gated behind the global NSFW switch.
                if (nsfwEnabled) {
                    AutoPostChip("Rebbit", autoPostRebbit) { onToggleAutoPost("rebbit", it) }
                }
                AutoPostChip("Y", autoPostY) { onToggleAutoPost("y", it) }
            }
        }

        // Relationship Status Badge — reflects the evolved dossier; hidden until one exists.
        if (!relationship.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Relationship: $relationship", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** A togglable pill for a single auto-post platform opt-in. */
@Composable
fun RowScope.AutoPostChip(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        onClick = { onToggle(!enabled) },
        modifier = Modifier.weight(1f),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = if (!enabled) androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enabled) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                label,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** A single human-readable line parsed out of the evolved dossier JSON. */
data class DossierFact(val category: String, val text: String)

/**
 * Flattens the dossier JSON ({relationship, user_traits, world_facts, milestones}) written
 * by "Evolve AI" into readable lines for the Memory Vault. Returns empty on null/blank/bad JSON.
 */
fun parseDossier(json: String?): List<DossierFact> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val obj = org.json.JSONObject(json)
        val facts = mutableListOf<DossierFact>()

        obj.optString("relationship").takeIf { it.isNotBlank() }?.let {
            facts += DossierFact("Relationship", it)
        }
        for ((label, key) in listOf("About you" to "user_traits", "World" to "world_facts")) {
            obj.optJSONObject(key)?.let { traits ->
                traits.keys().forEach { k ->
                    val v = traits.optString(k).takeIf { it.isNotBlank() } ?: return@forEach
                    facts += DossierFact(label, "${k.replace('_', ' ')}: $v")
                }
            }
        }
        obj.optJSONArray("milestones")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { facts += DossierFact("Milestone", it) }
            }
        }
        facts
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun MemoryVaultTab(
    memories: List<MemoryEntity>,
    dossierFacts: List<DossierFact>,
    onDelete: (String) -> Unit,
    onAdd: (String) -> Unit
) {
    var newMemoryText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Memory Vault", style = MaterialTheme.typography.titleMedium)
        Text("What the AI has learned about you.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newMemoryText,
                onValueChange = { newMemoryText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add important fact...") },
                singleLine = true
            )
            IconButton(onClick = {
                if (newMemoryText.isNotBlank()) {
                    onAdd(newMemoryText)
                    newMemoryText = ""
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            // AI-evolved dossier (read-only) — what "Evolve AI" produces.
            if (dossierFacts.isNotEmpty()) {
                item {
                    Text(
                        "AI Memory (auto-evolved)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(dossierFacts) { fact -> DossierFactItem(fact) }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        "Pinned facts",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            if (memories.isEmpty() && dossierFacts.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Add a fact above, or chat and tap \"Evolve AI\" to let the character build its own memory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            items(memories) { memory ->
                MemoryItem(memory = memory, onDelete = { onDelete(memory.id) })
            }
        }
    }
}

@Composable
fun DossierFactItem(fact: DossierFact) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = fact.category.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = fact.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MemoryItem(memory: MemoryEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.category.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(text = memory.text, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MessengerInboxPreview() {
    FancyAITheme {
        Text("Messenger Inbox Preview Placeholder")
    }
}
