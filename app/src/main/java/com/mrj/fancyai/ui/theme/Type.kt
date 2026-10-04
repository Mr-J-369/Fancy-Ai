package com.mrj.fancyai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

internal val FancyTypography = Typography().run {
    val system = FontFamily.Default
    Typography(
        displayLarge = displayLarge.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        displayMedium = displayMedium.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        displaySmall = displaySmall.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = system, fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontFamily = system, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = system),
        bodyMedium = bodyMedium.copy(fontFamily = system),
        bodySmall = bodySmall.copy(fontFamily = system),
        labelLarge = labelLarge.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = system, fontWeight = FontWeight.SemiBold),
    )
}
