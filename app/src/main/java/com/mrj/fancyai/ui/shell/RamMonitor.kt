package com.mrj.fancyai.ui.shell

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.SlateRaised
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun RamMonitorBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE) }
    var enabled by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_RAM_MONITOR, false)) }

    val listener = remember(prefs) {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_RAM_MONITOR) {
                enabled = prefs.getBoolean(KEY_RAM_MONITOR, false)
            }
        }
    }
    DisposableEffect(prefs, listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    if (!enabled) return

    val activityManager = remember(context) { context.getSystemService(ActivityManager::class.java) }
    var memInfo by remember {
        mutableStateOf(ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo))
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isResumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isResumed = true
            else if (event == Lifecycle.Event.ON_PAUSE) isResumed = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        val info = ActivityManager.MemoryInfo()
        while (isActive) {
            activityManager.getMemoryInfo(info)
            memInfo = ActivityManager.MemoryInfo().apply {
                availMem = info.availMem
                totalMem = info.totalMem
                threshold = info.threshold
                lowMemory = info.lowMemory
            }
            delay(1.seconds)
        }
    }

    val totalBytes = memInfo.totalMem
    val availBytes = memInfo.availMem
    val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
    val usedGb = usedBytes.toDouble() / 1073741824.0
    val totalGb = totalBytes.toDouble() / 1073741824.0
    val availGb = availBytes.toDouble() / 1073741824.0

    val dotColor = when {
        memInfo.lowMemory || availGb < 1.0 -> Danger
        availGb < 2.0 -> AccentSoft
        else -> Accent
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                .padding(top = 4.dp, end = 12.dp)
                .background(SlateRaised.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                .border(0.5.dp, Hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = String.format(Locale.ROOT, "RAM %.1f/%.1fG", usedGb, totalGb),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
