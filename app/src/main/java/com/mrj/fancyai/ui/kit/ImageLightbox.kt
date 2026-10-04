package com.mrj.fancyai.ui.kit

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun ImageLightbox(
    bitmap: Bitmap?,
    @StringRes description: Int = R.string.aura_result_description,
    onClose: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
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
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            } else {
                ZoomableImage(bitmap, description, onPrevious, onNext)
            }
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
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            content()
        }
    }
}

@Composable
private fun ZoomableImage(
    bitmap: Bitmap,
    @StringRes description: Int,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val scale = remember(bitmap) { Animatable(MIN_ZOOM) }
    val pan = remember(bitmap) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)

    LaunchedEffect(bitmap) {
        scale.snapTo(MIN_ZOOM)
        pan.snapTo(Offset.Zero)
    }

    fun bounds(atScale: Float, area: IntSize): Offset {
        val fit = minOf(
            area.width / bitmap.width.toFloat(),
            area.height / bitmap.height.toFloat(),
        )
        return Offset(
            (((bitmap.width * fit * atScale) - area.width) / 2f).coerceAtLeast(0f),
            (((bitmap.height * fit * atScale) - area.height) / 2f).coerceAtLeast(0f),
        )
    }

    fun settle(targetScale: Float, targetPan: Offset) {
        scope.launch { scale.animateTo(targetScale, spring()) }
        scope.launch { pan.animateTo(targetPan, spring()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    var travel = Offset.Zero
                    var pinched = false
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val moved = event.calculatePan()
                        val zoom = event.calculateZoom()
                        travel += moved
                        if (event.changes.count { it.pressed } > 1) pinched = true
                        if ((zoom != MIN_ZOOM) || ((scale.value > REST_ZOOM) && (moved != Offset.Zero))) {
                            val newScale = (scale.value * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val growth = newScale / scale.value
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val anchor = event.calculateCentroid(useCurrent = false)
                            val wanted =
                                ((anchor - center) * (MIN_ZOOM - growth)) + (pan.value * growth) + moved
                            val limit = bounds(newScale, size)
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                            scope.launch { scale.snapTo(newScale) }
                            scope.launch {
                                pan.snapTo(
                                    Offset(
                                        wanted.x.coerceIn(-limit.x, limit.x),
                                        wanted.y.coerceIn(-limit.y, limit.y),
                                    ),
                                )
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    val gestured = pinched || (travel.getDistance() > viewConfiguration.touchSlop)
                    if (gestured && (scale.value <= REST_ZOOM)) {
                        settle(MIN_ZOOM, Offset.Zero)
                        val farEnough = abs(travel.x) > PAGE_SLOP
                        val horizontal = abs(travel.x) > (abs(travel.y) * PAGE_BIAS)
                        if (pinched.not() && farEnough && horizontal) {
                            if (travel.x > 0f) previous?.invoke() else next?.invoke()
                        }
                    }
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale.value <= REST_ZOOM) {
                            val extra = DOUBLE_TAP_ZOOM - MIN_ZOOM
                            val limit = bounds(DOUBLE_TAP_ZOOM, size)
                            settle(
                                DOUBLE_TAP_ZOOM,
                                Offset(
                                    (((size.width / 2f) - tap.x) * extra).coerceIn(-limit.x, limit.x),
                                    (((size.height / 2f) - tap.y) * extra).coerceIn(-limit.y, limit.y),
                                ),
                            )
                        } else {
                            settle(MIN_ZOOM, Offset.Zero)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(description),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = pan.value.x
                translationY = pan.value.y
            },
        )
    }
}

private const val MIN_ZOOM = 1f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f
private const val REST_ZOOM = 1.01f
private const val PAGE_SLOP = 64f
private const val PAGE_BIAS = 1.25f
