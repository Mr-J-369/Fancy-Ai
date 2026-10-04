package com.mrj.fancyai.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.readPromptImage
import com.mrj.fancyai.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ImagePromptSection(image: File?, modifier: Modifier = Modifier, savedPrompt: String? = null) {
    var storedPrompt by remember(image?.absolutePath, image?.lastModified()) { mutableStateOf<String?>(null) }
    LaunchedEffect(image?.absolutePath, image?.lastModified()) {
        storedPrompt = withContext(Dispatchers.IO) {
            if ((image != null) && image.isFile) runCatching { readPromptImage(image) }.getOrNull() else null
        }
    }
    val prompt = storedPrompt?.takeIf(String::isNotBlank) ?: savedPrompt?.takeIf(String::isNotBlank) ?: return
    var expanded by rememberSaveable(prompt) { mutableStateOf(value = false) }
    Column(modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.aura_prompt), style = MaterialTheme.typography.labelMedium, color = Accent)
            Spacer(Modifier.weight(1f))
            Icon(painter = painterResource(if (expanded) R.drawable.ic_expand else R.drawable.ic_forward), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(expanded) {
            Text(
                prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}
