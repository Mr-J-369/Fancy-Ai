package com.mrj.fancyai.ui.kit

import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import java.io.File

@Composable
internal fun VideoPlayer(
    file: File,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
) {
    key(file.absolutePath, file.lastModified()) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val container = FrameLayout(context)
                val videoView = VideoView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    )
                }
                val controller = MediaController(context).apply {
                    setAnchorView(container)
                }
                videoView.setMediaController(controller)
                videoView.setVideoURI(Uri.fromFile(file))
                videoView.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true
                    if (autoPlay) videoView.start()
                    else {
                        videoView.seekTo(1)
                    }
                }
                container.addView(videoView)
                container.tag = videoView
                container
            },
            onRelease = { container ->
                (container.tag as? VideoView)?.stopPlayback()
            },
        )
    }
}

@Composable
internal fun VideoLightbox(
    file: File,
    onClose: () -> Unit,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayer(
                file = file,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                autoPlay = true,
            )
            val closeDescription = stringResource(R.string.settings_back)
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .size(60.dp)
                    .semantics { contentDescription = closeDescription }
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            content()
        }
    }
}
