package com.mrj.fancyai.ui.aura

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.service.IImageService
import com.mrj.fancyai.service.ImageService
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun AuraScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(app, scope) { AuraController(app, scope) }
    var pendingModelRemoval by remember { mutableStateOf<AuraModel?>(null) }
    var pendingModelExport by remember { mutableStateOf<AuraModel?>(null) }
    var lightbox by remember { mutableStateOf(false) }
    with(controller) {
        val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            controller.importModel(uri)
        }

        val exportModel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                pendingModelExport?.let { model ->
                    controller.exportModel(model, uri)
                }
            }
            pendingModelExport = null
        }

        val chooseSource = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            controller.chooseSource(uri)
        }

        LaunchedEffect(Unit) { refresh() }
        AuraTokenCountEffect()
        AuraImageServiceBinding(app, state.engine == AuraEngine.LOCAL) { service = it }
        AuraStopAndRecycleEffect()

        BackHandler {
            if (pendingModelRemoval != null) pendingModelRemoval = null
            else if (lightbox) lightbox = false
            else navigateBack(onBack)
        }
        pendingModelRemoval?.let { model ->
            AppDialog(
                onDismissRequest = { pendingModelRemoval = null },
                title = { Text(stringResource(R.string.remove_item_title, model.file.name)) },
                text = { Text(stringResource(R.string.aura_remove_model_message)) },
                confirmButton = {
                    TextButton(onClick = { pendingModelRemoval = null; removeModel(model) }) {
                        Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelMedium, color = Danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingModelRemoval = null }) {
                        Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }
        if (lightbox) ImageLightbox(state.result, onClose = { lightbox = false })

        AuraScreenContent(
            onBack = onBack,
            onChooseSource = { chooseSource.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onOpenResult = { lightbox = true },
            onImportModel = {
                importModel.launch(arrayOf("*/*"))
            },
            onSetPendingModelRemoval = { pendingModelRemoval = it },
        ) { model ->
            pendingModelExport = model
            exportModel.launch("${SdModel.sanitize(model.file.name)}.zip")
        }
    }
}

@Composable
private fun AuraController.AuraTokenCountEffect() {
    LaunchedEffect(service, state.engine, state.selectedModelPath, state.promptPrefix, state.prompt) {
        val imageService = service
        val model = state.selectedModel
        if ((state.engine != AuraEngine.LOCAL) || imageService == null || model == null) {
            state = state.copy(tokenCount = null)
            return@LaunchedEffect
        }
        delay(250.milliseconds)
        val count = runCatching {
            withContext(Dispatchers.IO) {
                imageService.countTokens(model.file.path, studioPrompt(state.promptPrefix, state.prompt))
            }
        }.getOrNull()
        state = state.copy(tokenCount = count)
    }
}

@Composable
internal fun AuraImageServiceBinding(
    app: Context,
    enabled: Boolean,
    onServiceChanged: (IImageService?) -> Unit,
) {
    val onService by rememberUpdatedState(onServiceChanged)
    DisposableEffect(app, enabled) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                onService(IImageService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onService(null)
            }
        }
        val bound = enabled && app.bindService(
            Intent(app, ImageService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose {
            if (bound) app.unbindService(connection)
            onService(null)
        }
    }
}

@Composable
private fun AuraController.AuraStopAndRecycleEffect() {
    DisposableEffect(Unit) {
        onDispose {
            stop()
            state.result?.recycle()
            state.sourcePreview?.recycle()
        }
    }
}

@Composable
private fun AuraController.AuraScreenContent(
    onBack: () -> Unit,
    onChooseSource: () -> Unit,
    onOpenResult: () -> Unit,
    onImportModel: () -> Unit,
    onSetPendingModelRemoval: (AuraModel) -> Unit,
    onExportModel: (AuraModel) -> Unit,
) {
    val engineSelectionEnabled = !state.generating && state.operation == null
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 0.dp),
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_aura),
            onBack = { navigateBack(onBack) },
            subtitle = stringResource(R.string.aura_eyebrow),
        )
        AuraNavigation(page) { page = it }
        state.operation?.let { OperationBanner(it, state.operationProgress) }
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            AuraPageBody(
                engineSelectionEnabled = engineSelectionEnabled,
                onChooseSource = onChooseSource,
                onOpenResult = onOpenResult,
                onImportModel = onImportModel,
                onSetPendingModelRemoval = onSetPendingModelRemoval,
                onExportModel = onExportModel,
            )
        }
        state.error?.let {
            Text(
                text = it,
                color = Danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        AuraDock()
    }
}

@Composable
private fun AuraController.AuraPageBody(
    engineSelectionEnabled: Boolean,
    onChooseSource: () -> Unit,
    onOpenResult: () -> Unit,
    onImportModel: () -> Unit,
    onSetPendingModelRemoval: (AuraModel) -> Unit,
    onExportModel: (AuraModel) -> Unit,
) {
    when (page) {
        AuraPage.STUDIO -> StudioPage(onChooseSource = onChooseSource, onOpenResult = onOpenResult)
        AuraPage.MODELS -> AuraBackendsPage(state) { engine ->
            page = engine.modelsPage
        }
        AuraPage.GET_MODELS -> GetModelsPage(
            onImportModel = onImportModel,
        )
        AuraPage.LAN -> AuraLanPage(
            state,
            onActivate = { selectEngine(AuraEngine.LAN) },
            onChanged = { address ->
                saveText("lan_address", address)
                if (state.engine == AuraEngine.LAN) reloadRemoteSettings()
            },
        )
        AuraPage.WEBUI, AuraPage.LOCAL_DREAM -> {
            val engine = if (page == AuraPage.LOCAL_DREAM) AuraEngine.LOCAL_DREAM else AuraEngine.WEBUI
            AuraRemoteConnectionPage(
                engine = engine,
                active = state.engine,
                enabled = engineSelectionEnabled,
                onActivate = { selectEngine(engine) },
                onChanged = { if (state.engine == engine) reloadRemoteSettings() },
                onSampling = { page = if (engine == AuraEngine.LOCAL_DREAM) AuraPage.LOCAL_DREAM_SAMPLING else AuraPage.WEBUI_SAMPLING },
            )
        }
        AuraPage.LOCAL_MODELS -> AuraLocalModelsPage(
            engineSelectionEnabled = engineSelectionEnabled,
            onSetPendingModelRemoval = onSetPendingModelRemoval,
            onExportModel = onExportModel,
            onGetModels = { page = AuraPage.GET_MODELS },
        )
        AuraPage.ADVANCED, AuraPage.WEBUI_SAMPLING, AuraPage.LOCAL_DREAM_SAMPLING -> AuraSamplingPage(page)
        AuraPage.ENHANCE -> EnhancePage()
    }
}

@Composable
private fun AuraController.AuraLocalModelsPage(
    engineSelectionEnabled: Boolean,
    onSetPendingModelRemoval: (AuraModel) -> Unit,
    onExportModel: (AuraModel) -> Unit,
    onGetModels: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { selectEngine(AuraEngine.LOCAL) },
            enabled = engineSelectionEnabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(if (state.engine == AuraEngine.LOCAL) R.string.cloud_active else R.string.aura_use_backend)) }
        Box(Modifier.weight(1f)) {
            ModelsPage(
                onRemove = onSetPendingModelRemoval,
                onExport = onExportModel,
                onGetModels = onGetModels,
            )
        }
    }
}

@Composable
private fun AuraController.AuraSamplingPage(page: AuraPage) {
    val engine = when (page) {
        AuraPage.WEBUI_SAMPLING -> AuraEngine.WEBUI
        AuraPage.LOCAL_DREAM_SAMPLING -> AuraEngine.LOCAL_DREAM
        else -> state.engine
    }
    when (engine) {
        AuraEngine.LAN -> AuraLanSettingsNote { this.page = AuraPage.LAN }
        AuraEngine.WEBUI, AuraEngine.LOCAL_DREAM -> AuraRemoteSamplingScreen(
            engine = engine,
            onChanged = { if (engine == state.engine) reloadRemoteSettings() },
            onSetup = { this.page = engine.modelsPage },
        )
        AuraEngine.LOCAL -> AdvancedPage()
    }
}
