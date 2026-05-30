package com.mrj.fancyai.data.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class AppConfig(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val route: String,
    val color: Color,
    val isDock: Boolean = false
)
