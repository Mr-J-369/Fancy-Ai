package com.mrj.fancyai.ui.shell

import android.widget.VideoView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun IntroScreen(onFinish: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val finished = remember { AtomicBoolean(false) }
    val finishOnce = { if (finished.compareAndSet(false, true)) onFinish() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clipToBounds(),
    ) {
        val coverWidth = maxHeight * FILM_ASPECT
        val wide = coverWidth >= maxWidth
        val width = if (wide) coverWidth else maxWidth
        val height = if (wide) maxHeight else maxWidth / FILM_ASPECT

        AndroidView(
            modifier = Modifier.requiredSize(width, height).align(Alignment.Center),
            onRelease = VideoView::stopPlayback,
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI("android.resource://${context.packageName}/${R.raw.root_intro}".toUri())
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        start()
                    }
                    setOnCompletionListener { finishOnce() }
                    setOnErrorListener { _, _, _ ->
                        finishOnce()
                        true
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.intro_skip),
            style = MaterialTheme.typography.labelLarge,
            color = AccentSoft,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.copy(alpha = 0.55f))
                .clickable { finishOnce() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

private const val FILM_ASPECT = 720f / 1280f
