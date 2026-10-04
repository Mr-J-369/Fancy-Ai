package com.mrj.fancyai.ui.binder

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.ui.characters.toCard
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun BinderScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(app, scope, snackbar) {
        BinderController(app, scope) { message ->
            scope.launch { snackbar.showSnackbar(message) }
        }
    }
    with(controller) {
        fun requestBack() {
            when {
                editing && preferences.configured -> editing = false
                savingMatch -> scope.launch { snackbar.showSnackbar(app.getString(R.string.binder_saving_wait)) }
                (generation.busy) || (match != null) -> exitRequested = true
                else -> onBack()
            }
        }


        DisposableEffect(controller) {
            onDispose { close() }
        }

        BackHandler(onBack = ::requestBack)

        BinderScreenContent(this, ::requestBack, snackbar)

        if (exitRequested) {
            BinderExitDialog(
                onDismiss = { exitRequested = false },
                onConfirm = {
                    exitRequested = false
                    stopGeneration()
                    onBack()
                },
            )
        }
    }
}


@Composable
internal fun BinderEmptyDeck(matches: Int, onFind: () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(
            stringResource(if (matches == 0) R.string.binder_empty_title else R.string.binder_empty_after_match),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.binder_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
        )
        Text(
            stringResource(R.string.binder_new_match),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)
                .background(Accent, RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onFind)
                .padding(vertical = 17.dp),
        )
    }
}

@Composable
internal fun BinderWorking(title: String, progress: Int?, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress == null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            LinearProgressIndicator(
                color = Accent,
                trackColor = Hairline,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(top = 14.dp).height(2.dp),
            )
        } else {
            ImageGenerationProgress(progress, Modifier.widthIn(max = 320.dp).padding(top = 14.dp), label = title)
        }
        Text(
            stringResource(R.string.action_stop),
            style = MaterialTheme.typography.labelMedium,
            color = Accent,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onStop)
                .padding(horizontal = 16.dp, vertical = 17.dp),
        )
    }
}

@Composable
internal fun BinderProfileCard(
    match: BinderMatch,
    saving: Boolean,
    onPass: () -> Unit,
    onMatch: () -> Unit,
    generating: Boolean,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().then(if (match.portrait?.isFile == true) Modifier.aspectRatio(0.78f) else Modifier.heightIn(min = 112.dp)).background(Slate),
            ) {
                MessageImage(
                    match.portrait?.absolutePath,
                    R.string.binder_portrait_description,
                    topPadding = 0.dp,
                    cornerRadius = 0.dp,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
                BinderProfileOverlay(match)
            }
        }
        item {
            content()
            BinderMatchDetails(match, saving = saving, generating = generating, onPass = onPass, onMatch = onMatch)
        }
    }
}

@Composable
private fun BinderProfileOverlay(match: BinderMatch) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to Ink,
                ),
            ),
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                stringResource(R.string.binder_name_age, match.card.name, match.age),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                stringResource(match.identity.label),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (match.card.handle.isNotBlank()) {
                Text(match.card.handle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun BinderProfileActions(
    saving: Boolean,
    generating: Boolean,
    onPass: () -> Unit,
    onMatch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.binder_pass),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).border(1.dp, Hairline, RoundedCornerShape(10.dp))
                .clickable(!saving && !generating, role = Role.Button, onClick = onPass).padding(vertical = 17.dp),
        )
        Text(
            stringResource(if (saving) R.string.state_saving else R.string.binder_match),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Ink,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).background(Accent, RoundedCornerShape(10.dp))
                .clickable(!saving && !generating, role = Role.Button, onClick = onMatch).padding(vertical = 17.dp),
        )
    }
}

@Composable
private fun BinderMatchDetails(
    match: BinderMatch,
    saving: Boolean,
    generating: Boolean,
    onPass: () -> Unit,
    onMatch: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        if (match.tags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                match.tags.forEach { tag ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentSoft,
                        modifier = Modifier.border(1.dp, Hairline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 16.dp))
        Text(
            stringResource(R.string.binder_behind_portrait),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
        )
        val context = LocalContext.current
        val bus = remember(context, match.card, match.characterId) {
            MacroBus(context, match.card.toCard(match.characterId), profile = null)
        }
        listOf(
            stringResource(R.string.field_personality) to match.card.personality,
            stringResource(R.string.binder_description) to match.card.description,
            stringResource(R.string.section_appearance).uppercase() to match.card.appearance,
        ).forEach { (label, value) ->
            if (value.isNotBlank()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 84.dp).padding(end = 8.dp))
                MessageMarkdown(bus.text(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
        BinderProfileActions(saving, generating, onPass, onMatch)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BinderQuestionnaire(
    value: BinderPreferences,
    onChange: (BinderPreferences) -> Unit,
    onFind: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.binder_question), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.binder_question_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).widthIn(max = 520.dp),
            )
        }
        item {
            BinderSection(stringResource(R.string.binder_interest), R.string.binder_interest_summary)
            BinderChoiceRow(BinderInterest.entries, value.interest, BinderInterest::label) {
                onChange(value.copy(interest = it))
            }
        }
        item {
            BinderSection(stringResource(R.string.binder_age), R.string.binder_age_summary)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    pluralStringResource(R.plurals.binder_age_value, value.minimumAge, value.minimumAge),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    pluralStringResource(R.plurals.binder_age_value, value.maximumAge, value.maximumAge),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val sliderColors = SliderDefaults.colors(thumbColor = AccentSoft, activeTrackColor = Accent, inactiveTrackColor = Hairline)
            RangeSlider(
                value = value.minimumAge.toFloat()..value.maximumAge.toFloat(),
                onValueChange = { range ->
                    onChange(
                        value.copy(
                            minimumAge = range.start.roundToInt().coerceIn(MINIMUM_AGE, MAXIMUM_AGE),
                            maximumAge = range.endInclusive.roundToInt().coerceIn(MINIMUM_AGE, MAXIMUM_AGE),
                        ),
                    )
                },
                valueRange = MINIMUM_AGE.toFloat()..MAXIMUM_AGE.toFloat(),
                steps = MAXIMUM_AGE - MINIMUM_AGE - 1,
                colors = sliderColors,
                track = { state ->
                    SliderDefaults.Track(
                        rangeSliderState = state, modifier = Modifier.height(3.dp), colors = sliderColors,
                        drawStopIndicator = null, drawTick = { _, _ -> }, thumbTrackGapSize = 2.dp,
                    )
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        item {
            BinderSection(stringResource(R.string.section_appearance).uppercase(), R.string.binder_appearance_summary)
            BinderChoiceRow(BinderStyle.entries, value.style, BinderStyle::label) {
                onChange(value.copy(style = it))
            }
        }
        item {
            BinderSection(stringResource(R.string.binder_nationality), R.string.binder_nationality_summary)
            PostInput(
                value = value.nationality,
                hint = stringResource(R.string.binder_nationality),
                singleLine = true,
                modifier = Modifier.padding(top = 8.dp),
                onValueChange = { onChange(value.copy(nationality = it.take(FIELD_LIMIT))) },
            )
        }
        item {
            BinderSection(stringResource(R.string.binder_traits), R.string.binder_traits_summary)
            BinderTraits(value, onChange)
        }
        item {
            Text(
                stringResource(R.string.binder_find),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Accent, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onFind).padding(vertical = 17.dp),
            )
            Text(
                stringResource(R.string.binder_engine_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BinderSection(title: String, @StringRes summary: Int) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = AccentSoft)
    Text(
        stringResource(summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun <T> BinderChoiceRow(
    choices: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelected: (T) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEach { choice ->
            val active = choice == selected
            Text(
                stringResource(label(choice)),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = 48.dp)
                    .background(if (active) Accent else Slate, RoundedCornerShape(8.dp))
                    .border(1.dp, if (active) Accent else Hairline, RoundedCornerShape(8.dp))
                    .selectable(selected = active, role = Role.RadioButton) { onSelected(choice) }
                    .padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun BinderTraits(value: BinderPreferences, onChange: (BinderPreferences) -> Unit) {
    val suggestions = listOf(
        R.string.binder_trait_kind to "kind",
        R.string.binder_trait_witty to "witty",
        R.string.binder_trait_confident to "confident",
        R.string.binder_trait_intellectual to "intellectual",
        R.string.binder_trait_romantic to "romantic",
        R.string.binder_trait_independent to "independent",
        R.string.label_creative to "creative",
        R.string.binder_trait_loyal to "loyal",
    )
    val choices = suggestions.map { (label, key) -> key to stringResource(label) } +
        value.traits.filter { trait -> suggestions.none { it.second == trait } }.map { it to it }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEach { (key, label) ->
            val active = key in value.traits
            val enabled = active || value.traits.size < MAXIMUM_TRAITS
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    active -> Ink
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                modifier = Modifier.heightIn(min = 48.dp)
                    .background(if (active) Accent else Slate, RoundedCornerShape(8.dp))
                    .toggleable(value = active, enabled = enabled, role = Role.Checkbox) {
                        onChange(
                            value.copy(
                                traits = if (active) value.traits - key else value.traits + key,
                            ),
                        )
                    }.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PostInput(
            value = value.customTrait,
            hint = stringResource(R.string.binder_custom_trait),
            singleLine = true,
            modifier = Modifier.weight(1f),
            onValueChange = { onChange(value.copy(customTrait = it.take(TRAIT_LIMIT))) },
        )
        val custom = value.customTrait.trim()
        val canAdd = custom.isNotBlank() && value.traits.none { it.equals(custom, ignoreCase = true) } &&
            value.traits.size < MAXIMUM_TRAITS
        Text(
            stringResource(R.string.action_add),
            style = MaterialTheme.typography.labelSmall,
            color = Accent.copy(alpha = if (canAdd) 1f else 0.35f),
            modifier = Modifier.heightIn(min = 48.dp).clickable(canAdd, role = Role.Button) {
                onChange(value.copy(traits = value.traits + custom, customTrait = ""))
            }.padding(start = 12.dp, top = 17.dp),
        )
    }
}
