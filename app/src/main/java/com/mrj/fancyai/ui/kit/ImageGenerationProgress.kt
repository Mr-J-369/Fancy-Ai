package com.mrj.fancyai.ui.kit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft

@Composable
internal fun ImageGenerationProgress(
    percent: Int,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.chat_image_generating),
    preview: Bitmap? = null,
) {
    val progress = percent.coerceIn(0, 100)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (preview != null && !preview.isRecycled && preview.width > 0 && preview.height > 0) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .fillMaxWidth()
                    .aspectRatio(preview.width.toFloat() / preview.height)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
            Text(stringResource(R.string.aura_progress, progress), style = MaterialTheme.typography.labelSmall, color = Accent)
        }
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}
