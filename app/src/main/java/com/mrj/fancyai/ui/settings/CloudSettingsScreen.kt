package com.mrj.fancyai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudModelInfo
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun CloudSettingsScreen(
    provider: CloudProvider,
    onBack: () -> Unit,
    onSelectionChanged: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, provider, scope) {
        CloudSettingsController(context, scope, provider)
    }
    DisposableEffect(controller) { onDispose(controller.runtime::cancel) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surfaceContainerLow,
                    0.28f to Ink,
                    1f to MaterialTheme.colorScheme.background,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            AppHeader(
                title = stringResource(cloudProviderName(provider)),
                subtitle = stringResource(
                    when (provider) {
                        CloudProvider.DEEPINFRA -> R.string.cloud_deepinfra_summary
                        CloudProvider.OPENROUTER -> R.string.cloud_openrouter_summary
                        CloudProvider.CUSTOM -> R.string.cloud_custom_summary
                    },
                ),
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                connectionFields(controller)
                modelCatalog(controller)
            }
            SettingsAction(
                text = stringResource(
                    when {
                        controller.active -> R.string.cloud_active
                        controller.configured -> R.string.cloud_use_provider
                        else -> R.string.cloud_finish_setup
                    },
                ),
                enabled = !controller.active,
                onClick = {
                    if (controller.activate()) onSelectionChanged()
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

private fun LazyListScope.connectionFields(controller: CloudSettingsController) {
    val context = controller.context
    val provider = controller.provider
    item {
        Text(text = stringResource(R.string.cloud_connection_section), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    }
    listOfNotNull(
        R.string.cloud_endpoint.takeIf { provider == CloudProvider.CUSTOM },
        R.string.cloud_api_key,
        R.string.cloud_model_id,
    ).forEach { field ->
        item {
            val (label, value, summary) = when (field) {
                R.string.cloud_endpoint -> Triple(field, controller.baseUrl, R.string.cloud_endpoint_summary)
                R.string.cloud_api_key -> Triple(
                    if (provider == CloudProvider.CUSTOM) R.string.cloud_api_key_optional else field,
                    controller.apiKey,
                    when (provider) {
                        CloudProvider.DEEPINFRA -> R.string.cloud_deepinfra_key_summary
                        CloudProvider.OPENROUTER -> R.string.cloud_openrouter_key_summary
                        CloudProvider.CUSTOM -> R.string.cloud_custom_key_summary
                    },
                )
                else -> Triple(field, controller.model, R.string.cloud_model_id_summary)
            }
            CloudField(
                label = stringResource(label),
                value = value,
                summary = stringResource(summary),
                keyboardType = if (field == R.string.cloud_endpoint) KeyboardType.Uri else KeyboardType.Text,
                secret = field == R.string.cloud_api_key,
            ) { next ->
                when (field) {
                    R.string.cloud_endpoint -> {
                        controller.baseUrl = next
                        CloudSettingsStore.saveCloudBaseUrl(context, next)
                    }
                    R.string.cloud_api_key -> {
                        controller.apiKey = next
                        CloudSettingsStore.saveCloudApiKey(context, provider, next)
                    }
                    else -> {
                        controller.model = next
                        controller.models.firstOrNull { (id) -> id == next.trim().removePrefix("models/") }?.let { selected ->
                            CloudSettingsStore.saveCloudModel(context, provider, selected)
                        } ?: CloudSettingsStore.saveCloudModel(context, provider, next)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.modelCatalog(controller: CloudSettingsController) {
    val context = controller.context
    val provider = controller.provider
    val matches = controller.matches
    item {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.cloud_models_section), modifier = Modifier.weight(1f), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
            Text(
                text = stringResource(
                    if (controller.loading) R.string.cloud_loading_models else R.string.cloud_refresh_models,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (controller.loading) MaterialTheme.colorScheme.outline else Accent,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !controller.loading, role = Role.Button, onClick = controller::fetchModels)
                    .padding(start = 12.dp, top = 14.dp),
            )
        }
    }
    if (controller.models.isNotEmpty()) {
        item {
            CloudField(
                label = stringResource(R.string.cloud_search_models),
                value = controller.query,
                summary = pluralStringResource(
                    R.plurals.cloud_models_found,
                    matches.size,
                    matches.size,
                ),
            ) {
                controller.query = it
                CloudSettingsStore.saveCloudModelQuery(context, provider, it)
            }
        }
        items(matches, key = { it.id }) { candidate ->
            SettingsModelRow(
                id = candidate.id,
                selected = controller.model.trim().removePrefix("models/") == candidate.id,
                summary = cloudModelSummary(candidate),
            ) {
                controller.model = candidate.id
                CloudSettingsStore.saveCloudModel(context, provider, candidate)
            }
        }
    }
    item {
        Text(
            text = stringResource(R.string.cloud_think_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (controller.errorMessage != 0) {
        item {
            Text(
                text = stringResource(controller.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CloudField(
    label: String,
    value: String,
    summary: String,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        PostInput(
            value = value,
            onValueChange = onChange,
            label = label,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )
    }
}

internal fun cloudProviderName(provider: CloudProvider): Int = when (provider) {
    CloudProvider.DEEPINFRA -> R.string.cloud_deepinfra
    CloudProvider.OPENROUTER -> R.string.cloud_openrouter
    CloudProvider.CUSTOM -> R.string.cloud_custom
}

@Composable
private fun cloudModelSummary(candidate: CloudModelInfo): String? {
    val context = candidate.contextLength?.takeIf { it > 0 }
    val priceIn = candidate.priceInput?.takeIf { it >= 0 }
    val priceOut = candidate.priceOutput?.takeIf { it >= 0 }
    return when {
        (context != null) && (priceIn != null) && (priceOut != null) -> stringResource(
            R.string.cloud_model_context_prices,
            compactTokenCount(context),
            compactPrice(priceIn),
            compactPrice(priceOut),
        )
        context != null -> stringResource(R.string.cloud_model_context, compactTokenCount(context))
        (priceIn != null) && (priceOut != null) -> stringResource(
            R.string.cloud_model_prices,
            compactPrice(priceIn),
            compactPrice(priceOut),
        )
        else -> null
    }
}

private fun compactTokenCount(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

private fun compactPrice(value: Double): String {
    val formatted = "%.4f".format(value).trimEnd('0').trimEnd('.').takeIf(String::isNotEmpty) ?: "0"
    return "$$formatted"
}
