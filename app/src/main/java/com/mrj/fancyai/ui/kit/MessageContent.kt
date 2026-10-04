package com.mrj.fancyai.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mrj.fancyai.ui.theme.Accent
import org.intellij.markdown.MarkdownElementTypes
import com.mrj.fancyai.R
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MessageImage(
    imagePath: String?,
    @StringRes description: Int,
    modifier: Modifier = Modifier,
    topPadding: Dp = 10.dp,
    cornerRadius: Dp = 10.dp,
    onRemove: (() -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    aspectRatioRange: ClosedFloatingPointRange<Float>? = null,
) {
    if (imagePath == null) return
    val context = LocalContext.current
    val source = File(imagePath)
    val file = if (source.isAbsolute) source else File(context.filesDir, imagePath)
    var expanded by remember { mutableStateOf(false) }
    key(imagePath, file.lastModified()) {
        val thumbnail = onRemove != null
        val edge = if (thumbnail) with(LocalDensity.current) { 64.dp.roundToPx() } else 1024
        var bitmap by remember(file, edge) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(file, edge) {
            bitmap = withContext(Dispatchers.IO) {
                try { decodeImage(context, Uri.fromFile(file), edge) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { null }
            }
        }
        val label = stringResource(description)
        val shape = RoundedCornerShape(if (thumbnail) 8.dp else cornerRadius)
        val ratio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
        val bounds = if (thumbnail) modifier.size(64.dp) else modifier.fillMaxWidth().padding(top = topPadding)
            .aspectRatio(aspectRatioRange?.let { ratio.coerceIn(it) } ?: ratio)
        Box(bounds.clip(shape).background(Color.Black)) {
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
            }
            bitmap?.let { image ->
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().clickable(enabled = !thumbnail, role = Role.Button) { expanded = true },
                    contentScale = if (thumbnail) ContentScale.Crop else contentScale,
                    alignment = alignment,
                    filterQuality = FilterQuality.High,
                )
            }
            onRemove?.let { remove ->
                val removeLabel = stringResource(R.string.chat_remove_attachment)
                Box(Modifier.align(Alignment.TopEnd).size(48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), shape)
                    .semantics { contentDescription = removeLabel }
                    .clickable(role = Role.Button, onClick = remove), contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (expanded) {
            MessageImageLightbox(
                file = file,
                description = description,
                onClose = { expanded = false },
            )
        }
    }
}

@Composable
private fun MessageImageLightbox(
    file: File,
    @StringRes description: Int,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var fullBitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        fullBitmap = withContext(Dispatchers.IO) {
            try { decodeImage(context, Uri.fromFile(file)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { null }
        }
    }
    ImageLightbox(
        bitmap = fullBitmap,
        description = description,
        onClose = onClose,
    )
}
@Composable
internal fun MessageMarkdown(
    content: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val references = remember { ReferenceLinkHandlerImpl() }
    val state = rememberMarkdownState(
        content = content.capitalizeFirstVisibleLetter(),
        retainState = true,
        referenceLinkHandler = references,
    )
    val inlineSettings = annotatorSettings(
        linkTextSpanStyle = markdownTypography().textLink,
        codeSpanStyle = style.copy(fontFamily = FontFamily.Monospace).toSpanStyle(),
        annotator = markdownAnnotator(),
        referenceLinkHandler = references,
    )
    Markdown(
        markdownState = state,
        annotator = markdownAnnotator { text, node ->
            if (node.type == MarkdownElementTypes.EMPH) {
                append(text.buildMarkdownAnnotatedString(node,
                    TextStyle(color = Accent, fontStyle = FontStyle.Italic), inlineSettings))
                true
            } else false
        },
        colors = markdownColor(text = color),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            h4 = MaterialTheme.typography.bodyLarge,
            h5 = MaterialTheme.typography.bodyMedium,
            h6 = MaterialTheme.typography.bodySmall,
            text = style,
            paragraph = style,
            ordered = style,
            bullet = style,
            list = style,
            quote = style.copy(fontStyle = FontStyle.Italic),
            code = style.copy(fontFamily = FontFamily.Monospace),
            inlineCode = style.copy(fontFamily = FontFamily.Monospace),
            table = style,
        ),
        modifier = modifier,
    )
}

internal fun String.capitalizeFirstVisibleLetter(): String {
    val index = indexOfFirst { !it.isWhitespace() && it !in "*_>#\"'“‘" }
    if (index < 0 || !this[index].isLetter() || this[index].isUpperCase()) return this
    val word = substring(index).takeWhile { !it.isWhitespace() }
        .trimEnd(',', '.', '!', '?', ':', ';', '*', '_', '\"', '\'', '”', '’', ')')
    if (word.any { !it.isLetter() && it != '\'' && it != '’' }) return this
    return replaceRange(index, index + 1, this[index].uppercase())
}

/** Local artwork decoded for its displayed size; full-resolution viewing uses ImageLightbox. */
@Composable
internal fun Artwork(
    path: String?,
    modifier: Modifier = Modifier,
    @DrawableRes resource: Int = 0,
    contentDescription: String? = null,
    revision: Int = 0,
    alignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var edge by remember { mutableIntStateOf(0) }
    val file = path?.let(::File)
    val modified = file?.lastModified()
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { edge = maxOf(it.width, it.height).coerceAtMost(2048) },
        contentAlignment = Alignment.Center,
    ) {
        key(path, modified, revision, edge) {
            var bitmap by remember(context, file, edge) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(context, file, edge) {
                if (file != null && edge > 0) {
                    bitmap = withContext(Dispatchers.IO) {
                        try { decodeImage(context, file.toUri(), edge) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                }
            }
            val loaded = bitmap
            when {
                loaded != null -> Image(loaded.asImageBitmap(), contentDescription,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment)
                resource != 0 -> Image(painterResource(resource), contentDescription,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment)
                else -> content()
            }
        }
    }
}

/** Shared, initially collapsed reasoning display for Chat and social posts/replies. */
@Composable
internal fun ThoughtProcess(content: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (content.isNotEmpty()) {
        Column(modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_thought_process),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_expand else R.drawable.ic_forward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                MessageMarkdown(
                    content = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
    }
}
