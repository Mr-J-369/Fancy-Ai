package com.mrj.fancyai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent

@Composable
internal fun SettingsStepper(
    title: String,
    value: String,
    summary: String,
    onLower: (() -> Unit)?,
    onHigher: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.86f),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton(
                mark = R.drawable.ic_minus,
                description = stringResource(R.string.settings_decrease, title),
                onClick = onLower,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = Accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StepperButton(
                mark = R.drawable.ic_add,
                description = stringResource(R.string.settings_increase, title),
                onClick = onHigher,
            )
        }
    }
}

@Composable
private fun StepperButton(
    mark: Int,
    description: String,
    onClick: (() -> Unit)?,
) {
    val enabled = onClick != null
    Box(
        Modifier
            .size(48.dp)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick ?: {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(mark), contentDescription = null, tint = if (enabled) Accent else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
        }
    }
}

internal fun <T> List<T>.below(value: T): T? = indexOf(value)
    .takeIf { it > 0 }
    ?.let { get(it - 1) }

internal fun <T> List<T>.above(value: T): T? = indexOf(value)
    .takeIf { (it in indices) && (it < lastIndex) }
    ?.let { get(it + 1) }
