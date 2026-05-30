package com.mrj.fancyai.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mrj.fancyai.data.db.entity.CharacterEntity
import java.io.File

/** Phone contact list — pick who to voice-call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneContactsScreen(
    navController: NavHostController,
    viewModel: PhoneContactsViewModel,
    onCall: (String) -> Unit
) {
    val contacts by viewModel.contacts.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text("Phone", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No contacts yet. Create a character in Messenger first.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(contacts) { c ->
                    ContactRow(
                        character = c,
                        avatar = viewModel.resolveAvatar(c.avatarRef),
                        onClick = { onCall(c.id) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun ContactRow(character: CharacterEntity, avatar: File?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(avatar = avatar, size = 48.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(character.name, fontWeight = FontWeight.Bold)
            if (character.handle.isNotBlank()) {
                Text(character.handle, fontSize = 12.sp, color = Color.Gray)
            }
        }
        Icon(Icons.Filled.Call, contentDescription = "Call ${character.name}", tint = Color(0xFF4CAF50))
    }
}

@Composable
private fun Avatar(avatar: File?, size: androidx.compose.ui.unit.Dp) {
    Surface(modifier = Modifier.size(size).clip(CircleShape), color = MaterialTheme.colorScheme.primaryContainer) {
        if (avatar != null) {
            AsyncImage(model = avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(size * 0.22f), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun PhoneScreen(
    charId: String,
    navController: NavHostController,
    viewModel: PhoneViewModel
) {
    val character by viewModel.character.collectAsState()
    val name = character?.name ?: "Calling…"
    val avatar = viewModel.resolveAvatar(character?.avatarRef)

    val context = LocalContext.current
    var hasAudio by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudio = granted
        if (granted) viewModel.startCall()
    }

    val startCall = {
        if (hasAudio) viewModel.startCall() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (!viewModel.isCallActive) {
        InactiveCallUI(
            name = name,
            avatar = avatar,
            onStartCall = startCall,
            onNavigateBack = { navController.popBackStack() }
        )
    } else {
        ActiveCallUI(
            name = name,
            avatar = avatar,
            isListening = viewModel.isListening,
            userText = viewModel.userText,
            aiResponse = viewModel.aiResponse,
            onStartListening = { viewModel.startListening() },
            onStopListening = { viewModel.stopListening() },
            onEndCall = { viewModel.endCall(); navController.popBackStack() }
        )
    }
}

@Composable
private fun InactiveCallUI(
    name: String,
    avatar: File?,
    onStartCall: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Avatar(avatar = avatar, size = 120.dp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Voice call", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                CallActionButton(icon = Icons.Filled.Call, label = "Call", color = Color(0xFF4CAF50), onClick = onStartCall)
                CallActionButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", color = Color.Gray, onClick = onNavigateBack)
            }
        }
    }
}

@Composable
private fun ActiveCallUI(
    name: String,
    avatar: File?,
    isListening: Boolean,
    userText: String,
    aiResponse: String,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Avatar(avatar = avatar, size = 100.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (isListening) "Listening…" else "On call",
            style = MaterialTheme.typography.labelMedium,
            color = if (isListening) MaterialTheme.colorScheme.primary else Color.Gray
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Transcript: what the character is saying + what you said.
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(aiResponse.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text("You", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Text(userText.ifEmpty { "Tap the mic to speak" }, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            CallActionButton(
                icon = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (isListening) "Stop" else "Speak",
                color = if (isListening) Color(0xFFFF9800) else MaterialTheme.colorScheme.primary,
                onClick = if (isListening) onStopListening else onStartListening
            )
            CallActionButton(icon = Icons.Filled.Call, label = "End", color = Color(0xFFF44336), onClick = onEndCall)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(64.dp).clip(CircleShape),
            color = color
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
