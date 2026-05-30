package com.mrj.fancyai.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mrj.fancyai.data.db.entity.CharacterEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    navController: NavHostController,
    viewModel: GamesViewModel
) {
    val activeGame = viewModel.activeGame

    // Hardware back exits the active game to the hub instead of leaving the screen.
    BackHandler(enabled = activeGame != null) { viewModel.exitGame() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activeGame?.title ?: "Games Hub", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (activeGame != null) viewModel.exitGame() 
                        else navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (activeGame == null) {
            GameHubList(onSelect = { viewModel.selectGame(it) }, modifier = Modifier.padding(padding))
        } else {
            GamePlayArea(viewModel, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
fun GameHubList(onSelect: (GameType) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Interactive Experiences", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        
        GameType.entries.forEach { game ->
            Card(
                onClick = { onSelect(game) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(game.icon, fontSize = 28.sp)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(game.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(game.desc, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPicker(
    characters: List<CharacterEntity>,
    selected: CharacterEntity?,
    onSelect: (CharacterEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "No characters",
            onValueChange = {},
            readOnly = true,
            label = { Text("Play with") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            characters.forEach { c ->
                DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GamePlayArea(viewModel: GamesViewModel, modifier: Modifier = Modifier) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val gameText = if (viewModel.streamingText.isNotEmpty()) viewModel.streamingText else viewModel.gameText
    var inputText by remember { mutableStateOf("") }
    val notStarted = viewModel.gameText == "Ready to play?"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // AI Story/Game Text (+ optional generated scene image)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                viewModel.gameImage?.let { img ->
                    AsyncImage(
                        model = img,
                        contentDescription = "Scene",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (viewModel.generatingImage) {
                    Text("🎨 Generating scene…", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = gameText,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Controls
        if (viewModel.isThinking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
        } else if (notStarted) {
            // Pick who you're playing with, then start.
            CharacterPicker(
                characters = viewModel.availableCharacters,
                selected = viewModel.gameCharacter,
                onSelect = { viewModel.selectCharacter(it) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { viewModel.startGame() },
                enabled = viewModel.gameCharacter != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (viewModel.gameCharacter != null) "START SESSION" else "Create a character first",
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What do you do?") },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendAction(inputText)
                            inputText = ""
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}
