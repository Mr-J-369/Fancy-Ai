package com.mrj.fancyai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mrj.fancyai.di.ServiceLocator
import com.mrj.fancyai.service.SocialScheduler
import com.mrj.fancyai.ui.navigation.NavGraph
import com.mrj.fancyai.ui.theme.FancyAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.initialize(this)

        // Apply the user's autonomous-posting preference (enabled + interval) from settings.
        val settings = ServiceLocator.getSettingsRepository()
        SocialScheduler.apply(
            this,
            enabled = settings.autoPostEnabled,
            intervalMinutes = settings.socialPostIntervalMinutes
        )

        enableEdgeToEdge()
        setContent {
            // Re-themes live when the user changes the Theme setting.
            val themeMode by settings.themeModeFlow.collectAsState()
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            FancyAITheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph()
                }
            }
        }
    }
}
