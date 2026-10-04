package com.mrj.fancyai.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun PostInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = if (singleLine) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
        imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
    ),
    label: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    content: @Composable (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label ?: hint },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        label = label?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        placeholder = if (hint.isNotEmpty()) {
            { Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        trailingIcon = content,
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SlateRaised,
            unfocusedContainerColor = SlateRaised,
            disabledContainerColor = SlateRaised,
            errorContainerColor = SlateRaised,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Hairline,
            disabledBorderColor = Hairline.copy(alpha = 0.5f),
            errorBorderColor = MaterialTheme.colorScheme.error,
            focusedLabelColor = Accent,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = Accent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
internal fun PromptEditor(
    value: String,
    section: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Text(
        section,
        style = MaterialTheme.typography.labelSmall,
        color = AccentSoft,
    )
    PostInput(
        value = value,
        hint = hint,
        singleLine = false,
        minLines = 4,
        maxLines = 10,
        onValueChange = onValueChange,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(
        stringResource(R.string.action_reset_prompt),
        style = MaterialTheme.typography.labelMedium,
        color = Accent,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onReset)
            .padding(top = 14.dp, end = 14.dp),
    )
}

@Composable
internal fun EditorField(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PostInput(
            value = value,
            hint = hint,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
