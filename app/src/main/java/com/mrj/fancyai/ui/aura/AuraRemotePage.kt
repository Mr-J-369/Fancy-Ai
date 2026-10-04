package com.mrj.fancyai.ui.aura

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.service.InferenceForegroundService
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.util.PrivateHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun AuraLanPage(state: AuraState, onActivate: () -> Unit, onChanged: (String) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val host by AuraLanHost.state.collectAsState()
    var address by remember { mutableStateOf(auraLanAddress(app)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val networkAccess = rememberAuraNetworkAccess { error = R.string.aura_lan_permission }
    val enabled = !state.generating && state.operation == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.aura_lan), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.aura_lan_note), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        AuraSection(stringResource(R.string.aura_lan_client)) {
            PostInput(value = address, onValueChange = {
                address = it
                error = null
                onChanged(it)
            }, enabled = enabled && !loading,
                label = stringResource(R.string.cloud_endpoint), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { networkAccess {
                loading = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { checkAuraLan(address) }
                        onActivate()
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = (failure as? AuraNetworkException)?.messageResource ?: R.string.aura_remote_connection_failed }
                    finally { loading = false }
                }
            } }, enabled = enabled && !loading && validAuraLanAddress(address), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (loading) R.string.aura_lan_connecting else if (state.engine == AuraEngine.LAN) R.string.cloud_active else R.string.aura_use_backend))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider()
        AuraSection(stringResource(R.string.aura_lan_host)) {
            Text(stringResource(R.string.aura_lan_host_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (host.address.isNotBlank()) {
                Text(host.address, style = MaterialTheme.typography.titleMedium, color = AccentSoft)
                Text(pluralStringResource(R.plurals.aura_lan_jobs, host.jobs, host.jobs), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(app.getString(R.string.aura_lan), host.address),
                    )
                }) { Text(stringResource(R.string.aura_lan_copy_address)) }
            }
            OutlinedButton(onClick = {
                if (host.address.isNotBlank()) AuraLanHost.stop()
                else networkAccess {
                    InferenceForegroundService.start(app)
                    scope.launch { withContext(Dispatchers.IO) { AuraLanHost.start(app) } }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (host.address.isBlank()) R.string.aura_lan_start else R.string.aura_lan_stop))
            }
            host.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun AuraLanSettingsNote(onSetup: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.aura_lan), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.aura_lan_note))
        OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.aura_remote_open_setup)) }
    }
}

@Composable
internal fun rememberAuraNetworkAccess(address: String? = null, onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else onDenied()
    }
    val url = address?.toHttpUrlOrNull()
    val local = address == null || url?.let {
        it.host.endsWith(".local") || '.' !in it.host || runCatching {
            PrivateHttp.routePrivateHttp(it.newBuilder().scheme("http").build().toString())
        }.isSuccess
    } == true
    return { action ->
        if (local && Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            pending = action
            permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else action()
    }
}

private class AuraRemoteSetup(
    private val app: Context,
    engine: AuraEngine,
    savedConnections: androidx.compose.runtime.MutableState<List<AuraRemoteConnection>>,
) {
    var connection by mutableStateOf(remoteConnection(app, engine))
    var settings by mutableStateOf(remoteSettings(app, connection))
    var catalog by mutableStateOf(remoteCatalog(app, connection))
    var loading by mutableStateOf(false)
    var saved by savedConnections
    var error by mutableIntStateOf(0)

    fun changed(value: AuraRemoteConnection, onChanged: () -> Unit) {
        saveRemoteConnection(app, value)
        connection = value
        catalog = remoteCatalog(app, value)
        settings = remoteSettings(app, value)
        error = 0
        onChanged()
    }

    suspend fun connect(onChanged: () -> Unit) {
        try {
            if (connection.engine == AuraEngine.LOCAL_DREAM) {
                withContext(Dispatchers.IO) {
                    AuraHttp(connection).request("tokenize", buildJsonObject {
                        put("prompt", JsonPrimitive(""))
                    })
                }
                onChanged()
                return
            }
            val fetched = withContext(Dispatchers.IO) { AuraHttp(connection).discover() }
            val prefs = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
            prefs.edit {
                putString("${remoteKey(connection)}.catalog", fetched.toJson())
                putStringSet("webui_addresses", prefs.getStringSet("webui_addresses", emptySet()).orEmpty() + connection.url)
            }
            saved = savedWebUiConnections(app)
            catalog = fetched
            if (settings.model in fetched.models) {
                settings = settings.withSampling(fetched)
                saveRemoteSettings(app, connection, settings)
            }
            onChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = (failure as? AuraNetworkException)?.messageResource ?: R.string.aura_remote_request_failed
        } finally { loading = false }
    }

    fun removeModel(onChanged: () -> Unit) {
        val prefs = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
        val root = remoteKey(connection)
        val model = settings.model
        val remaining = catalog?.let { it.copy(models = it.models - model) }
        prefs.edit {
            listOf("steps", "cfg", "width", "height", "sampler", "scheduler", "seed", "seed_locked",
                "prefix", "upscaler", "denoising", "redraw").forEach { remove("$root.$model.$it") }
            remove("$root.model")
            putString("$root.catalog", remaining?.toJson())
        }
        catalog = remaining
        settings = remoteSettings(app, connection)
        onChanged()
    }

    fun forgetServer(onChanged: () -> Unit) {
        val prefs = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
        val root = remoteKey(connection)
        prefs.edit {
            prefs.all.keys.filter { it.startsWith("$root.") }.forEach(::remove)
            putStringSet("webui_addresses", saved.filter { remoteKey(it) != root }.map { it.url }.toSet())
            remove("remote.${connection.engine.name}.url")
            remove("remote.${connection.engine.name}.username")
            remove("remote.${connection.engine.name}.password")
        }
        connection = AuraRemoteConnection(connection.engine)
        catalog = null
        settings = AuraRemoteSettings()
        saved = savedWebUiConnections(app)
        error = 0
        onChanged()
    }
}

@Composable
internal fun AuraRemoteConnectionPage(
    engine: AuraEngine,
    active: AuraEngine,
    enabled: Boolean,
    onActivate: () -> Unit,
    onChanged: () -> Unit,
    onSampling: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val savedConnections = remember(engine) { mutableStateOf(if (engine == AuraEngine.WEBUI) savedWebUiConnections(app) else emptyList()) }
    val setup = remember(engine) { AuraRemoteSetup(app, engine, savedConnections) }
    val networkAccess = rememberAuraNetworkAccess(setup.connection.url) { setup.error = R.string.aura_lan_permission }
    with(setup) {
        val editable = enabled && !loading
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(engine.label), style = MaterialTheme.typography.headlineSmall)
            Text(if (engine == AuraEngine.LOCAL_DREAM) stringResource(R.string.aura_local_dream_note)
                else stringResource(R.string.aura_remote_setup_note, "--api --listen"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved.isNotEmpty()) EnumSelector(stringResource(R.string.aura_saved_servers), connection.url,
                saved.map { item -> item.url to { if (editable) changed(item, onChanged) } })
            PostInput(
                label = stringResource(R.string.cloud_endpoint), value = connection.url,
                onValueChange = { changed(connection.copy(url = it), onChanged) },
                enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
            if (engine == AuraEngine.WEBUI) {
                AuraWebUiCredentials(
                    connection = connection,
                    editable = editable,
                    onChanged = { changed(it, onChanged) },
                )
            }
            AuraRemoteConnectionActions(
                setup = setup,
                engine = engine,
                active = active,
                editable = editable,
                onChanged = onChanged,
                onSampling = onSampling,
                onConnect = {
                    networkAccess {
                        loading = true
                        error = 0
                        scope.launch {
                            setup.connect {
                                onChanged()
                                if (engine == AuraEngine.LOCAL_DREAM) onActivate()
                            }
                        }
                    }
                },
            )
            catalog?.let { available ->
                AuraRemoteModelsSection(
                    app = app,
                    setup = setup,
                    available = available,
                    engine = engine,
                    active = active,
                    editable = editable,
                    onActivate = onActivate,
                    onChanged = onChanged,
                    onSampling = onSampling,
                )
            }
            Text(stringResource(if (engine == AuraEngine.LOCAL_DREAM) R.string.aura_remote_stopped else R.string.aura_remote_stop_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuraRemoteModelsSection(
    app: Context,
    setup: AuraRemoteSetup,
    available: WebUiCatalog,
    engine: AuraEngine,
    active: AuraEngine,
    editable: Boolean,
    onActivate: () -> Unit,
    onChanged: () -> Unit,
    onSampling: () -> Unit,
) {
    Text(stringResource(R.string.aura_saved_models), style = MaterialTheme.typography.labelLarge, color = AccentSoft)
    Text(stringResource(R.string.aura_saved_models_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (available.models.isEmpty()) Text(stringResource(R.string.aura_remote_models_empty), style = MaterialTheme.typography.bodyMedium)
    else EnumSelector(
        title = stringResource(R.string.aura_model_section),
        selected = setup.settings.model.ifBlank { stringResource(R.string.aura_remote_choose_model) },
        options = available.models.map { model -> model to {
            if (editable) {
                app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).edit {
                    putString("${remoteKey(setup.connection)}.model", model)
                }
                setup.settings = remoteSettings(app, setup.connection).withSampling(available)
                saveRemoteSettings(app, setup.connection, setup.settings)
                onChanged()
            }
        } },
    )
    if (setup.settings.model.isNotBlank()) TextButton(enabled = editable, onClick = { setup.removeModel(onChanged) }) {
        Text(stringResource(R.string.aura_remove_saved_model), color = Danger)
    }
    Button(enabled = editable, onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (active == engine) R.string.aura_selected_backend else R.string.aura_use_backend))
    }
    OutlinedButton(onClick = onSampling, enabled = editable, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.generation_title))
    }
}

@Composable
private fun AuraWebUiCredentials(
    connection: AuraRemoteConnection,
    editable: Boolean,
    onChanged: (AuraRemoteConnection) -> Unit,
) {
    PostInput(
        label = stringResource(R.string.aura_remote_username),
        value = connection.username,
        onValueChange = { onChanged(connection.copy(username = it)) },
        enabled = editable,
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
    )
    PostInput(
        label = stringResource(R.string.aura_remote_password),
        value = connection.password,
        onValueChange = { onChanged(connection.copy(password = it)) },
        enabled = editable,
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun AuraRemoteConnectionActions(
    setup: AuraRemoteSetup,
    engine: AuraEngine,
    active: AuraEngine,
    editable: Boolean,
    onChanged: () -> Unit,
    onSampling: () -> Unit,
    onConnect: () -> Unit,
) {
    OutlinedButton(
        enabled = editable && setup.connection.url.isNotBlank(),
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val label = when {
            setup.loading -> R.string.aura_remote_connecting
            engine == AuraEngine.LOCAL_DREAM && active == engine -> R.string.cloud_active
            else -> R.string.aura_remote_connect
        }
        Text(stringResource(label))
    }
    if (setup.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (setup.error != 0) Text(stringResource(setup.error), color = Danger, style = MaterialTheme.typography.bodySmall)
    if (engine == AuraEngine.LOCAL_DREAM) OutlinedButton(
        onClick = onSampling,
        enabled = editable,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.generation_title)) }
    if (engine == AuraEngine.WEBUI && setup.connection.url.isNotBlank()) TextButton(
        enabled = editable,
        onClick = { setup.forgetServer(onChanged) },
    ) {
        Text(stringResource(R.string.aura_forget_server), color = Danger)
    }
}

@Composable
internal fun AuraRemoteSamplingScreen(engine: AuraEngine, onChanged: () -> Unit, onSetup: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val connection = remember(engine) { remoteConnection(app, engine) }
    var settings by remember(connection) { mutableStateOf(remoteSettings(app, connection)) }
    val catalog = remember(connection) { remoteCatalog(app, connection) }
    fun change(value: AuraRemoteSettings) {
        saveRemoteSettings(app, connection, value)
        settings = value
        onChanged()
    }
    val value = settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(engine.label), style = MaterialTheme.typography.headlineSmall)
        if (catalog == null && engine != AuraEngine.LOCAL_DREAM) {
            Text(stringResource(R.string.aura_remote_setup_required))
            OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.aura_remote_open_setup)) }
        } else {
            Text(value.model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AuraSection(stringResource(R.string.section_sampling)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(stringResource(R.string.aura_steps), value.steps, false, { change(value.copy(steps = it)) }, Modifier.weight(1f))
                    NumericField(stringResource(R.string.aura_guidance), value.cfg, true, { change(value.copy(cfg = it)) }, Modifier.weight(1f))
                }
                if (engine == AuraEngine.LOCAL_DREAM) EnumSelector(stringResource(R.string.aura_schedule), value.scheduler,
                    listOf("dpm", "dpm_karras", "dpm_sde", "dpm_sde_karras", "euler_a", "euler_a_karras", "euler", "euler_karras", "lcm")
                        .map { it to { change(value.copy(scheduler = it)) } })
                else EnumSelector(stringResource(R.string.aura_sampler), value.sampler,
                    catalog!!.samplers.map { it to { change(value.copy(sampler = it)) } })
                if (catalog != null && catalog.schedulers.isNotEmpty()) EnumSelector(stringResource(R.string.aura_schedule),
                    catalog.schedulers.firstOrNull { it.first == value.scheduler }?.second.orEmpty(),
                    catalog.schedulers.map { (id, label) -> label to { change(value.copy(scheduler = id)) } })
            }
            AuraSection(stringResource(R.string.aura_remote_resolution)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField(stringResource(R.string.aura_remote_width), value.width, false, { change(value.copy(width = it)) }, Modifier.weight(1f))
                    NumericField(stringResource(R.string.aura_remote_height), value.height, false, { change(value.copy(height = it)) }, Modifier.weight(1f))
                }
                Text(stringResource(R.string.aura_remote_resolution_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AuraSection(stringResource(R.string.field_seed)) {
                NumericField(stringResource(R.string.field_seed), value.seed, false, { change(value.copy(seed = it)) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { change(value.copy(seedLocked = !value.seedLocked)) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (value.seedLocked) R.string.aura_unlock_seed else R.string.aura_lock_seed))
                    }
                    OutlinedButton(onClick = { change(value.copy(seed = SeedPolicy.roll().toString())) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.aura_random_seed))
                    }
                }
            }
        }
    }
}
