package com.mrj.fancyai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mrj.fancyai.R
import com.mrj.fancyai.service.voice.VoiceModelKind
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultSample = stringResource(R.string.voice_tts_sample)

    val controller = remember(context, scope, defaultSample) { VoiceSettingsController(context, scope, defaultSample) }


    DisposableEffect(controller) { onDispose { controller.close() } }

    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.voice_title), onBack = onBack, subtitle = stringResource(R.string.voice_subtitle))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        ) {
                item {
                    VoiceRecognitionSettings(controller)

                    Spacer(Modifier.height(28.dp))

                    VoiceSpeechSettings(controller)
                    Spacer(Modifier.height(28.dp))
                    Text(stringResource(R.string.voice_playback), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    Spacer(Modifier.height(10.dp))
                    SettingsStepper(
                        title = stringResource(R.string.voice_playback_start),
                        value = pluralStringResource(R.plurals.voice_playback_characters, controller.playbackCharacters, controller.playbackCharacters),
                        summary = stringResource(R.string.voice_playback_start_detail),
                        onLower = if (controller.playbackCharacters > 25) ({ controller.savePlaybackCharacters(controller.playbackCharacters - 25) }) else null,
                        onHigher = if (controller.playbackCharacters < 250) ({ controller.savePlaybackCharacters(controller.playbackCharacters + 25) }) else null,
                    )
                    Spacer(Modifier.height(16.dp))
                    CompactSwitchRow(
                        title = stringResource(R.string.voice_playback_skip_actions),
                        summary = stringResource(R.string.voice_playback_skip_actions_detail),
                        checked = controller.skipActions,
                        onCheckedChange = { controller.skipActions = it; VoiceSettingsStore.saveSkipActions(context, it) },
                    )
                }
            }
    }

    controller.picker?.let { kind -> VoiceModelSheet(controller, kind, sheetState) }
}

@Composable
private fun VoiceRecognitionSettings(controller: VoiceSettingsController) {
    val context = LocalContext.current
    val language = LocalLocale.current.platformLocale.displayName
    val partial by controller.partialFlow.collectAsState()
    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) controller.listen() else controller.stt.error = R.string.voice_permission_denied
    }
    val sttApiKey = VoiceSettingsStore.apiKey(context, controller.stt.provider)
    val sttAvailable = remember(controller.stt.provider, controller.stt.model, controller.localRevision, sttApiKey) {
        controller.sttAvailable
    }
    val sttProviderLabel = stringResource(controller.stt.provider.label)
    val sttStatus = voiceStatus(
        provider = controller.stt.provider,
        providerLabel = sttProviderLabel,
        apiKey = sttApiKey,
        model = controller.stt.model,
        androidAvailable = controller.recognitionAvailable,
    )
    val spokenText = if (controller.listening) partial else controller.transcript.orEmpty()
    val sttFeedback = when {
        controller.stt.error != 0 -> stringResource(controller.stt.error)
        spokenText.isNotBlank() -> spokenText
        controller.listening -> stringResource(R.string.voice_listening)
        controller.transcript != null -> stringResource(R.string.voice_nothing_heard)
        else -> null
    }
    VoiceCapability(
        section = stringResource(R.string.voice_stt_section),
        mark = stringResource(R.string.voice_stt_mark),
        title = stringResource(R.string.voice_stt_title, sttProviderLabel),
        summary = stringResource(controller.stt.provider.recognitionSummary, sttProviderLabel),
        status = if (controller.stt.provider == VoiceProvider.LOCAL) stringResource(if (sttAvailable) R.string.voice_local_ready else R.string.voice_local_missing) else sttStatus,
        available = sttAvailable,
        feedback = sttFeedback,
        feedbackLabel = if (spokenText.isNotBlank()) {
            stringResource(R.string.voice_heard)
        } else {
            null
        },
        action = stringResource(
            if (controller.listening) {
                R.string.voice_stop_listening
            } else {
                R.string.voice_test_microphone
            },
        ),
        actionEnabled = sttAvailable,
        onAction = {
            if (controller.listening) {
                controller.activeStt.get()?.cancel()
            } else if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                controller.listen()
            } else {
                requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    ) {
        VoiceProviderSelector(controller.stt.provider) { controller.selectProvider(VoiceModelKind.STT, it) }
        when (controller.stt.provider) {
            VoiceProvider.LOCAL -> LocalVoiceControls(
                VoiceModelKind.STT, controller.stt.model, "", controller.sample,
                onModel = { controller.setModel(VoiceModelKind.STT, it) }, onVoice = {},
                onChanged = { controller.localRevision++ }, stopSpeech = controller::stopTests,
            )
            VoiceProvider.ANDROID -> Text(text = stringResource(R.string.voice_language, language), modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            else -> {
                VoiceModelControl(controller.stt.model) { controller.openPicker(VoiceModelKind.STT) }
                Text(text = stringResource(R.string.voice_cloud_stt_privacy, sttProviderLabel), modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VoiceSpeechSettings(controller: VoiceSettingsController) {
    val context = LocalContext.current
    val ttsApiKey = VoiceSettingsStore.apiKey(context, controller.tts.provider)
    val ttsLanguageReady = remember(controller.tts.model, controller.localRevision) { controller.ttsLanguageReady }
    val ttsAvailable = remember(controller.tts.provider, controller.tts.model, controller.ttsVoice, controller.localRevision, ttsApiKey) {
        controller.ttsAvailable
    }
    val ttsProviderLabel = stringResource(controller.tts.provider.label)
    val ttsStatus = voiceStatus(
        provider = controller.tts.provider,
        providerLabel = ttsProviderLabel,
        apiKey = ttsApiKey,
        model = controller.tts.model,
        androidAvailable = true,
    )
    val supportedVoices = controller.tts.models.firstOrNull {
        it.id == controller.tts.model.trim().removePrefix("models/")
    }?.voices.orEmpty()

    VoiceCapability(
        section = stringResource(R.string.voice_tts_section),
        mark = stringResource(R.string.voice_tts_mark),
        title = stringResource(R.string.voice_tts_title, ttsProviderLabel),
        summary = stringResource(controller.tts.provider.speechSummary, ttsProviderLabel),
        status = if (controller.tts.provider == VoiceProvider.LOCAL) stringResource(when {
            ttsAvailable -> R.string.voice_local_ready
            !ttsLanguageReady -> R.string.voice_choose_language
            controller.tts.model.isNotBlank() && controller.ttsVoice.isBlank() -> R.string.voice_local_voice_missing
            else -> R.string.voice_local_missing
        }) else ttsStatus,
        available = ttsAvailable,
        feedback = if (controller.tts.error == 0) null else stringResource(controller.tts.error),
        action = stringResource(
            if (controller.speaking) R.string.voice_playing else R.string.voice_play_text,
        ),
        actionEnabled = controller.sample.isNotBlank() && !controller.speaking && ttsAvailable,
        onAction = controller::testSpeech,
    ) {
        VoiceProviderSelector(controller.tts.provider) { controller.selectProvider(VoiceModelKind.TTS, it) }
        if (controller.tts.provider == VoiceProvider.LOCAL) {
            LocalVoiceControls(VoiceModelKind.TTS, controller.tts.model, controller.ttsVoice, controller.sample,
                onModel = { controller.setModel(VoiceModelKind.TTS, it) }, onVoice = {
                    controller.stopTests()
                    controller.ttsVoice = it
                    VoiceSettingsStore.saveVoice(context, controller.tts.provider, controller.tts.model, it)
                }, onChanged = { controller.localRevision++ }, stopSpeech = controller::stopTests)
        } else if (controller.tts.provider != VoiceProvider.ANDROID) {
            VoiceModelControl(controller.tts.model) { controller.openPicker(VoiceModelKind.TTS) }
            VoiceVoiceEditor(
                value = controller.ttsVoice,
                supported = supportedVoices,
            ) { value ->
                controller.ttsVoice = value
                controller.tts.error = 0
                VoiceSettingsStore.saveVoice(context, controller.tts.provider, controller.tts.model, value)
            }
            Text(text = stringResource(R.string.voice_cloud_tts_privacy, ttsProviderLabel), modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.voice_tts_text_label),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
            modifier = Modifier.padding(top = 14.dp),
        )
        PostInput(
            value = controller.sample,
            hint = stringResource(R.string.voice_tts_text_hint),
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.padding(top = 6.dp).heightIn(min = 84.dp),
            onValueChange = { value ->
                controller.sample = value
                controller.tts.error = 0
                VoiceSettingsStore.saveTestText(context, value)
            },
        )
    }
}
