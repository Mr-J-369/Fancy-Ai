package com.mrj.fancyai.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GraphicsBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mrj.fancyai.di.ServiceLocator
import com.mrj.fancyai.ui.theme.FancyAITheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalAppConfig(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val route: String,
    val color: Color,
    val isDock: Boolean = false
)

@Composable
fun HomeScreen(navController: NavHostController) {
    val apps = remember {
        listOf(
            LocalAppConfig("messenger", "Messenger", Icons.AutoMirrored.Filled.Chat, "messenger", Color(0xFF2196F3), isDock = true),
            LocalAppConfig("imaging", "Imaging", Icons.Default.AutoAwesome, "imaging", Color(0xFFE91E63), isDock = true),
            LocalAppConfig("gallery", "Gallery", Icons.Default.Star, "gallery", Color(0xFFFFC107), isDock = true),
            LocalAppConfig("settings", "Settings", Icons.Default.Settings, "settings", Color(0xFF607D8B), isDock = true),
            
            LocalAppConfig("ustagram", "Ustagram", Icons.Default.Face, "social/ustagram", Color(0xFF9C27B0)),
            LocalAppConfig("rebbit", "Rebbit", Icons.Default.Notifications, "social/rebbit", Color(0xFFFF5722)),
            LocalAppConfig("y", "Y", Icons.Default.Public, "social/y", Color(0xFF03A9F4)),
            LocalAppConfig("phone", "Phone", Icons.Default.Call, "phone", Color(0xFF4CAF50)),
            LocalAppConfig("games", "Games", Icons.Default.PlayArrow, "games", Color(0xFFF44336))
        )
    }

    // Rebbit is the NSFW platform: hidden from the launcher unless NSFW is enabled in Settings.
    val settings = ServiceLocator.getSettingsRepository()
    val nsfwEnabled by settings.nsfwEnabledFlow.collectAsState()
    val autoPostActive by settings.autoPostEnabledFlow.collectAsState()
    val visibleApps = apps.filter { nsfwEnabled || it.id != "rebbit" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                GraphicsBrush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460)
                    )
                )
            )
    ) {
        // Decorative background elements (mesh-like circles)
        Box(
            modifier = Modifier
                .size(400.dp)
                .align(Alignment.TopEnd)
                .background(Color(0x11D0BCFF), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomStart)
                .background(Color(0x11EFB8C8), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            HomeStatusBar()

            if (autoPostActive) {
                Surface(
                    color = Color(0x3300C853),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF69F0AE), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Auto-posting is active — characters will post on their own.",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            HomeClockWidget()

            Spacer(modifier = Modifier.height(48.dp))

            // App Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(visibleApps.filter { !it.isDock }) { app ->
                    AppIcon(app) {
                        navController.navigate(app.route)
                    }
                }
            }

            // Dock
            HomeDock(visibleApps.filter { it.isDock }) { app ->
                navController.navigate(app.route)
            }
        }
    }
}

@Composable
fun HomeStatusBar() {
    // Spacer to ensure we respect the top system inset
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun HomeClockWidget() {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = time,
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            letterSpacing = (-2).sp
        )
        Text(
            text = date,
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.8f),
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
fun AppIcon(app: LocalAppConfig, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            color = app.color.copy(alpha = 0.9f),
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = app.icon,
                    contentDescription = app.name,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = app.name,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun HomeDock(dockApps: List<LocalAppConfig>, onAppClick: (LocalAppConfig) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .height(88.dp),
        color = Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dockApps.forEach { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onAppClick(app) }
                        .padding(top = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        color = app.color,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                app.icon,
                                contentDescription = app.name,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = app.name,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    FancyAITheme {
        HomeScreen(navController = rememberNavController())
    }
}
