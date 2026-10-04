package com.mrj.fancyai

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mrj.fancyai.service.InferenceForegroundService
import com.mrj.fancyai.ui.shell.HomeScreen
import com.mrj.fancyai.ui.shell.RamMonitorBadge
import com.mrj.fancyai.ui.theme.FancyTheme
import com.mrj.fancyai.update.InAppUpdater

class MainActivity : ComponentActivity() {
    private lateinit var inAppUpdater: InAppUpdater
    private var onPhoneCallHidden: (() -> Unit)? = null
    internal var inPhonePictureInPicture by mutableStateOf(value = false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        inAppUpdater = InAppUpdater(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        InferenceForegroundService.start(this)
        setContent {
            FancyTheme {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen { InferenceForegroundService.exit(this@MainActivity) }
                    if (!inPhonePictureInPicture) {
                        RamMonitorBadge()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        inAppUpdater.check()
    }

    override fun onStop() {
        onPhoneCallHidden?.also { onPhoneCallHidden = null }?.invoke()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPhonePictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        inAppUpdater.unregister()
        super.onDestroy()
    }

    internal fun configurePhonePictureInPicture(
        active: Boolean,
        title: String,
        onHidden: (() -> Unit)?,
    ) {
        onPhoneCallHidden = if (active) onHidden else null
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
            .setAutoEnterEnabled(active)
            .apply {
                if (active) {
                    val sourceRect = Rect()
                    if (window.decorView.getGlobalVisibleRect(sourceRect)) {
                        setSourceRectHint(sourceRect)
                    }
                }
                setTitle(title)
            }
            .build()
        setPictureInPictureParams(params)
    }
}
