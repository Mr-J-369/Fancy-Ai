package com.mrj.fancyai.ui.aura

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.AuraOrientation
import com.mrj.fancyai.sd.AuraSizes
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.DitBackend
import com.mrj.fancyai.sd.DitCacheMode
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.sd.hd.UpscaleStyle
import com.mrj.fancyai.sd.hd.UpscalerModels
import com.mrj.fancyai.service.llm.MacroBus
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private val auraJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable
internal data class AuraImageMetadata(
    @Required val prompt: String = "",
    @Required val negative: String = "",
    @Required val seed: Long = 0L,
    @Required val steps: Int = 0,
    @Required val cfg: Float = 0f,
    @Required val width: Int = 0,
    @Required val height: Int = 0,
    @Required val sampler: String = "",
    @Required val schedule: String = "",
    @Required val model: String = "",
    @Required val modelType: String = "",
    @Required val runtime: String = "",
    @Required val backend: String = "",
    @Required val memoryPolicy: String = "",
    @Required val denoising: Float = 0f,
    val guidance: Float = 3.5f,
    val ditCacheMode: String = "",
    val ditCacheOption: String = "",
    val generationDurationMs: Long? = null,
    val enhanced: Boolean = false,
) {
    fun toJson(): JsonObject = JsonObject(auraJson.encodeToJsonElement(serializer(), this).jsonObject + ("provider" to JsonPrimitive("aura")))
}

internal val AuraImageMetadata.isRemote: Boolean get() = (runtime == AuraEngine.WEBUI.name || runtime == AuraEngine.LOCAL_DREAM.name)

internal fun AuraImageMetadata.toCopyText(context: Context): String = buildList {
    prompt.trim().takeIf(String::isNotEmpty)?.let(::add)
    negative.trim().takeIf(String::isNotEmpty)?.let {
        add(context.getString(R.string.aura_copy_negative_prompt, it))
    }
    val runtimeName = context.getString(
        when {
            runtime == AuraEngine.LOCAL_DREAM.name -> R.string.aura_local_dream
            isRemote -> R.string.aura_webui
            runtime == SdRuntimeType.MNN.name -> R.string.engines_mnn
            runtime == SdRuntimeType.DIT.name -> R.string.aura_runtime_dit
            else -> R.string.aura_runtime_qnn
        },
    )
    val processorName = context.getString(
        when {
            runtime == AuraEngine.LOCAL_DREAM.name -> R.string.aura_local_dream
            isRemote -> R.string.aura_webui
            runtime == SdRuntimeType.MNN.name -> MnnBackend.fromName(backend).label()
            runtime == SdRuntimeType.DIT.name -> DitBackend.fromName(backend).label()
            else -> R.string.aura_runtime_qnn
        },
    )
    add(
        context.getString(
            R.string.aura_copy_generation_settings,
            steps,
            if (isRemote) sampler else context.getString(SamplerType.fromName(sampler).label()),
            if (isRemote) schedule else context.getString(
                runCatching { Schedule.valueOf(schedule) }.getOrDefault(Schedule.KARRAS).label(),
            ),
            context.getString(R.string.format_decimal_two_places, cfg),
            seed,
            width,
            height,
            model,
            runtimeName,
            processorName,
        ),
    )
    if (runtime == SdRuntimeType.DIT.name && DitCacheMode.fromName(ditCacheMode) != DitCacheMode.DISABLED) {
        val modeLabel = context.getString(DitCacheMode.fromName(ditCacheMode).label())
        add(
            if (ditCacheOption.isBlank()) {
                context.getString(R.string.aura_copy_dit_cache, modeLabel)
            } else {
                context.getString(R.string.aura_copy_dit_cache_options, modeLabel, ditCacheOption)
            },
        )
    }
    if (denoising > 0f) {
        add(
            context.getString(
                R.string.aura_copy_denoising,
                context.getString(R.string.format_decimal_two_places, denoising),
            ),
        )
    }
}.joinToString("\n")

internal data class AuraRenderedImage(val file: File, val metadata: AuraImageMetadata)

internal enum class AuraEngine(val label: Int) {
    LOCAL(R.string.settings_this_device),
    WEBUI(R.string.aura_webui),
    LOCAL_DREAM(R.string.aura_local_dream),
    LAN(R.string.aura_lan),
}

internal val AuraEngine.modelsPage: AuraPage
    get() = when (this) {
        AuraEngine.LOCAL -> AuraPage.LOCAL_MODELS
        AuraEngine.WEBUI -> AuraPage.WEBUI
        AuraEngine.LOCAL_DREAM -> AuraPage.LOCAL_DREAM
        AuraEngine.LAN -> AuraPage.LAN
    }

internal enum class AuraDockAction {
    STOP,
    CREATE,
    INSTALL_UPSCALER,
    ENHANCE;

    fun enabled(state: AuraState): Boolean = (this == STOP || state.operation == null) && when (this) {
        CREATE -> state.hasGenerationModel && state.prompt.isNotBlank() &&
            (state.engine != AuraEngine.LOCAL || (state.steps.toIntOrNull()?.let { it in 1..150 } == true &&
                state.cfg.toFloatOrNull()?.let { it > 0f } == true))
        INSTALL_UPSCALER -> true
        ENHANCE -> state.enhancementAvailability == EnhancementAvailability.READY &&
            if (state.engine == AuraEngine.LOCAL) {
                state.resultPath != null && state.selectedModel != null
            } else {
                state.hasGenerationModel
            }
        STOP -> true
    }
}

internal enum class AuraPage(
    @StringRes val label: Int,
    val parent: AuraPage? = null,
) {
    STUDIO(R.string.aura_tab_studio),
    MODELS(R.string.aura_tab_models),
    GET_MODELS(R.string.aura_tab_get_models),
    ADVANCED(R.string.generation_title),
    ENHANCE(R.string.aura_tab_enhance),
    LOCAL_MODELS(R.string.aura_model_section, MODELS),
    WEBUI(R.string.aura_webui, MODELS),
    LOCAL_DREAM(R.string.aura_local_dream, MODELS),
    LOCAL_DREAM_SAMPLING(R.string.generation_title, LOCAL_DREAM),
    LAN(R.string.aura_lan, MODELS),
    WEBUI_SAMPLING(R.string.generation_title, WEBUI),
    ;

    val section: AuraPage get() = parent?.section ?: this
}

internal data class AuraRemoteConnection(
    val engine: AuraEngine,
    val url: String = "",
    val username: String = "",
    val password: String = "",
)

internal data class AuraRemoteSettings(
    val model: String = "",
    val steps: String = "20",
    val cfg: String = "7",
    val width: String = "512",
    val height: String = "512",
    val sampler: String = "",
    val scheduler: String = "",
    val seed: String = "0",
    val seedLocked: Boolean = false,
    val promptPrefix: String = "",
    val upscaler: String = "",
    val denoising: Float = 0.75f,
    val redraw: Float = 0f,
) {
    fun withSampling(catalog: WebUiCatalog): AuraRemoteSettings = copy(
        sampler = sampler.takeIf { it in catalog.samplers } ?: catalog.samplers.firstOrNull().orEmpty(),
        scheduler = scheduler.takeIf { it in catalog.schedulers.map { row -> row.first } }
            ?: catalog.schedulers.firstOrNull()?.first.orEmpty(),
    )
}

internal data class AuraRemoteGenerationConfig(
    val connection: AuraRemoteConnection,
    val catalog: WebUiCatalog?,
    val upscaler: String,
    override val metadata: AuraImageMetadata,
) : AuraGenerationConfig {
    override val seed: Long get() = metadata.seed
}

internal const val KEY_AURA_ENGINE = "image_engine"
internal fun auraEngine(context: Context): AuraEngine {
    return runCatching {
        AuraEngine.valueOf(context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).getString(KEY_AURA_ENGINE, AuraEngine.LOCAL.name).orEmpty())
    }.getOrDefault(AuraEngine.LOCAL)
}

internal sealed interface AuraGenerationConfig {
    val seed: Long
    val metadata: AuraImageMetadata
}

internal fun AuraGenerationConfig.withSeed(seed: Long): AuraGenerationConfig = when (this) {
    is AuraLocalGenerationConfig -> copy(seed = seed, metadata = metadata.copy(seed = seed))
    is AuraRemoteGenerationConfig -> copy(metadata = metadata.copy(seed = seed))
    is AuraLanGenerationConfig -> this
}

internal data class AuraLocalGenerationConfig(
    val modelPath: String,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val guidance: Float = 3.5f,
    val maskPath: String = "",
    val referenceImagePaths: List<String> = emptyList(),
    override val seed: Long,
    val sampler: String,
    val schedule: String,
    val vPred: Boolean,
    val backend: String,
    val memoryPolicy: String,
    val imageRefine: Boolean,
    val imageRefineStrength: Float,
    val imageRefinePrompt: String,
    val ditCacheMode: String,
    val ditCacheOption: String,
    val upscalerStyle: UpscaleStyle,
    override val metadata: AuraImageMetadata,
) : AuraGenerationConfig

internal fun auraGenerationConfig(
    context: Context,
    imagePrompt: String,
    includePromptPrefix: Boolean = true,
    rollSeed: Boolean = true,
): AuraGenerationConfig {
    return when (auraEngine(context)) {
        AuraEngine.LOCAL -> auraLocalGenerationConfig(context, imagePrompt, includePromptPrefix, rollSeed)
        AuraEngine.WEBUI, AuraEngine.LOCAL_DREAM -> auraRemoteGenerationConfig(context, imagePrompt, includePromptPrefix, rollSeed)
        AuraEngine.LAN -> auraLanGenerationConfig(context, imagePrompt, includePromptPrefix)
    }
}

private fun resolveDimensions(preferences: SharedPreferences, model: File, modelInfo: AuraModel, isDit: Boolean): Pair<Int, Int> {
    val (presetW, presetH) = modelInfo.size(modelInfo.savedOrientation(preferences))
    if (!isDit) return presetW to presetH
    val rawW = preferences.getString("model.${model.name}.$KEY_CUSTOM_WIDTH", null)?.toIntOrNull() ?: presetW
    val rawH = preferences.getString("model.${model.name}.$KEY_CUSTOM_HEIGHT", null)?.toIntOrNull() ?: presetH
    val width = (rawW.coerceIn(512, 2048) / 64) * 64
    val height = (rawH.coerceIn(512, 2048) / 64) * 64
    return width to height
}

private fun resolveSeed(preferences: SharedPreferences, model: File, rollSeed: Boolean): Long {
    val seedLocked = preferences.getBoolean("model.${model.name}.$KEY_SEED_LOCKED", false)
    return if (seedLocked || !rollSeed) {
        (preferences.getString("model.${model.name}.$KEY_SEED", DEFAULT_SEED) ?: DEFAULT_SEED).toLong()
    } else {
        SeedPolicy.roll().also {
            preferences.edit { putString("model.${model.name}.$KEY_SEED", it.toString()) }
        }
    }
}

private fun resolveDitCacheOption(preferences: SharedPreferences, modelName: String, mode: DitCacheMode): String {
    fun pref(key: String, default: String): String =
        preferences.getString("model.$modelName.$key", default)?.trim()?.ifEmpty { default } ?: default
    return when (mode) {
        DitCacheMode.DISABLED -> ""
        DitCacheMode.EASYCACHE -> buildString {
            append("threshold=").append(pref(KEY_DIT_EASYCACHE_THRESHOLD, DEFAULT_DIT_EASYCACHE_THRESHOLD))
            append(",start=").append(pref(KEY_DIT_EASYCACHE_START, DEFAULT_DIT_EASYCACHE_START))
            append(",end=").append(pref(KEY_DIT_EASYCACHE_END, DEFAULT_DIT_EASYCACHE_END))
        }
        DitCacheMode.DBCACHE, DitCacheMode.TAYLORSEER, DitCacheMode.CACHE_DIT -> buildString {
            append("threshold=").append(pref(KEY_DIT_CACHE_THRESHOLD, DEFAULT_DIT_CACHE_THRESHOLD))
            append(",warmup=").append(pref(KEY_DIT_CACHE_WARMUP, DEFAULT_DIT_CACHE_WARMUP))
            append(",fn=").append(pref(KEY_DIT_CACHE_FN, DEFAULT_DIT_CACHE_FN))
            append(",bn=").append(pref(KEY_DIT_CACHE_BN, DEFAULT_DIT_CACHE_BN))
            append(",max_cached=").append(pref(KEY_DIT_CACHE_MAX_CACHED, DEFAULT_DIT_CACHE_MAX_CACHED))
            append(",max_continuous=").append(pref(KEY_DIT_CACHE_MAX_CONTINUOUS, DEFAULT_DIT_CACHE_MAX_CONTINUOUS))
            if ((mode == DitCacheMode.TAYLORSEER) || (mode == DitCacheMode.CACHE_DIT)) {
                append(",derivatives=").append(pref(KEY_DIT_CACHE_DERIVATIVES, DEFAULT_DIT_CACHE_DERIVATIVES))
                append(",interval=").append(pref(KEY_DIT_CACHE_INTERVAL, DEFAULT_DIT_CACHE_INTERVAL))
            }
            val scmDynamic = preferences.getBoolean("model.$modelName.$KEY_DIT_CACHE_SCM_DYNAMIC", true)
            append(",scm_dynamic=").append(if (scmDynamic) "1" else "0")
            val scmMask = preferences.getString("model.$modelName.$KEY_DIT_CACHE_SCM_MASK", "")?.trim().orEmpty()
            if (scmMask.isNotEmpty()) {
                append(",scm_mask=").append(scmMask)
            }
        }
        DitCacheMode.SPECTRUM -> buildString {
            append("warmup=").append(pref(KEY_DIT_SPECTRUM_WARMUP, DEFAULT_DIT_SPECTRUM_WARMUP))
            append(",window=").append(pref(KEY_DIT_SPECTRUM_WINDOW, DEFAULT_DIT_SPECTRUM_WINDOW))
            append(",m=").append(pref(KEY_DIT_SPECTRUM_M, DEFAULT_DIT_SPECTRUM_M))
            append(",w=").append(pref(KEY_DIT_SPECTRUM_W, DEFAULT_DIT_SPECTRUM_W))
            append(",lam=").append(pref(KEY_DIT_SPECTRUM_LAM, DEFAULT_DIT_SPECTRUM_LAM))
            append(",flex=").append(pref(KEY_DIT_SPECTRUM_FLEX, DEFAULT_DIT_SPECTRUM_FLEX))
            append(",stop=").append(pref(KEY_DIT_SPECTRUM_STOP, DEFAULT_DIT_SPECTRUM_STOP))
        }
    }
}

internal fun auraLocalGenerationConfig(
    context: Context,
    imagePrompt: String,
    includePromptPrefix: Boolean = true,
    rollSeed: Boolean = true,
): AuraLocalGenerationConfig {
    val preferences = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val models = SdModel.list(context).mapNotNull { model ->
        runCatching { model to SdModel.packageType(model) }.getOrNull()
    }
    val selectedPath = preferences.getString(KEY_MODEL, null)
    val (model, packageType) = models.firstOrNull { it.first.path == selectedPath }
        ?: models.firstOrNull() ?: error("No valid image models available")
    if (model.path != selectedPath) preferences.edit { putString(KEY_MODEL, model.path) }

    val steps = (preferences.getString("model.${model.name}.$KEY_STEPS", DEFAULT_STEPS) ?: DEFAULT_STEPS).toInt()
    val cfg = (preferences.getString("model.${model.name}.$KEY_CFG", DEFAULT_CFG) ?: DEFAULT_CFG).toFloat()
    val guidance = (preferences.getString("model.${model.name}.$KEY_DISTILLED_GUIDANCE", DEFAULT_DISTILLED_GUIDANCE) ?: DEFAULT_DISTILLED_GUIDANCE).toFloatOrNull() ?: 3.5f
    val modelInfo = AuraModel(model, packageType.model, packageType.runtime, chunkLimit = null)
    val isDit = packageType.model == SdModelType.DIT
    val defaultSampler = if (isDit) SamplerType.EULER.name else SamplerType.LCM.name
    val defaultSchedule = if (isDit) Schedule.BETA.name else Schedule.KARRAS.name
    val (width, height) = resolveDimensions(preferences, model, modelInfo, isDit)
    val seed = resolveSeed(preferences, model, rollSeed)
    val macros = MacroBus(context)
    val prompt = macros.text(if (includePromptPrefix) studioPrompt(preferences.getString("model.${model.name}.$KEY_PROMPT_PREFIX", "").orEmpty(), imagePrompt) else imagePrompt)
    val negative = macros.text(preferences.getString(KEY_NEGATIVE_PROMPT, "").orEmpty().trim())
    val sampler = preferences.getString("model.${model.name}.$KEY_SAMPLER", defaultSampler) ?: defaultSampler
    val schedule = preferences.getString("model.${model.name}.$KEY_SCHEDULE", defaultSchedule) ?: defaultSchedule
    val backendDefault = if (isDit) DitBackend.AUTOMATIC.name else MnnBackend.AUTOMATIC.name
    val backend = preferences.getString("model.${model.name}.$KEY_BACKEND", backendDefault) ?: backendDefault
    val memoryPolicy = preferences.getString("model.${model.name}.$KEY_MEMORY_POLICY", MnnMemoryPolicy.LOW_MEMORY.name) ?: MnnMemoryPolicy.LOW_MEMORY.name
    val ditCacheModeEnum = if (isDit) {
        DitCacheMode.fromName(preferences.getString("model.${model.name}.$KEY_DIT_CACHE_MODE", DitCacheMode.DISABLED.name))
    } else {
        DitCacheMode.DISABLED
    }
    val ditCacheMode = ditCacheModeEnum.name
    val ditCacheOption = resolveDitCacheOption(preferences, model.name, ditCacheModeEnum)
    val metadata = AuraImageMetadata(
        prompt = prompt,
        negative = negative,
        seed = seed,
        steps = steps,
        cfg = cfg,
        guidance = guidance,
        width = width,
        height = height,
        sampler = sampler,
        schedule = schedule,
        model = model.name,
        modelType = packageType.model.name,
        runtime = packageType.runtime.name,
        backend = backend,
        memoryPolicy = memoryPolicy,
        denoising = 0f,
        ditCacheMode = if (ditCacheModeEnum != DitCacheMode.DISABLED) ditCacheMode else "",
        ditCacheOption = ditCacheOption,
    )
    return AuraLocalGenerationConfig(
        modelPath = model.path,
        prompt = prompt,
        negativePrompt = negative,
        width = width,
        height = height,
        steps = steps,
        cfg = cfg,
        guidance = guidance,
        seed = seed,
        sampler = sampler,
        schedule = schedule,
        vPred = preferences.getBoolean("model.${model.name}.$KEY_V_PRED", SdModel.hasVPredMarker(model.path)),
        backend = backend,
        memoryPolicy = memoryPolicy,
        imageRefine = preferences.getBoolean("model.${model.name}.$KEY_IMAGE_REFINE", false),
        imageRefinePrompt = preferences.getString(
            "model.${model.name}.$KEY_IMAGE_REFINE_PROMPT", DEFAULT_IMAGE_REFINE_PROMPT,
        ).orEmpty(),
        imageRefineStrength = preferences.getFloat(
            "model.${model.name}.$KEY_IMAGE_REFINE_STRENGTH",
            0.20f,
        ),
        ditCacheMode = ditCacheMode,
        ditCacheOption = ditCacheOption,
        upscalerStyle = runCatching {
            UpscaleStyle.valueOf(preferences.getString("model.${model.name}.$KEY_UPSCALER_STYLE", UpscaleStyle.PHOTO.name) ?: UpscaleStyle.PHOTO.name)
        }.getOrDefault(UpscaleStyle.PHOTO),
        metadata = metadata,
    )
}

internal fun studioPrompt(prefix: String, prompt: String): String {
    val cleanPrefix = prefix.trim().trimEnd(',', ' ')
    val cleanPrompt = prompt.trim().trimStart(',', ' ')
    return listOf(cleanPrefix, cleanPrompt).asSequence().filter(String::isNotBlank).joinToString(", ")
}

internal const val AURA_PREFERENCES = "aura"
internal const val KEY_MODEL = "model"
internal const val KEY_PROMPT_PREFIX = "prompt_prefix_draft"
internal const val KEY_PROMPT = "prompt_draft"
internal const val KEY_NEGATIVE_PROMPT = "negative_prompt_draft"
internal const val KEY_STEPS = "steps"
internal const val KEY_CFG = "cfg"
internal const val KEY_DISTILLED_GUIDANCE = "distilled_guidance"
internal const val DEFAULT_DISTILLED_GUIDANCE = "3.5"
internal const val KEY_SAMPLER = "sampler"
internal const val KEY_SCHEDULE = "schedule"
internal const val KEY_BACKEND = "backend"
internal const val KEY_MEMORY_POLICY = "memory_policy"
internal const val KEY_SEED = "seed"
internal const val KEY_SEED_LOCKED = "seed_locked"
internal const val KEY_V_PRED = "v_pred"
internal const val DEFAULT_IMAGE_REFINE_PROMPT = "photorealistic detail, natural skin texture, subtle skin pores, detailed irises, clear eyes, natural eyelashes, individual hair strands, fine fabric texture, realistic lighting, crisp fine details"
internal const val KEY_IMAGE_REFINE_PROMPT = "image_refine_prompt"
internal const val KEY_IMAGE_REFINE = "image_refine"
internal const val KEY_IMAGE_REFINE_STRENGTH = "image_refine_strength"
internal const val KEY_DIT_CACHE_MODE = "dit_cache_mode"
internal const val KEY_DIT_EASYCACHE_THRESHOLD = "dit_easycache_threshold"
internal const val KEY_DIT_EASYCACHE_START = "dit_easycache_start"
internal const val KEY_DIT_EASYCACHE_END = "dit_easycache_end"
internal const val KEY_DIT_CACHE_THRESHOLD = "dit_cache_threshold"
internal const val KEY_DIT_CACHE_WARMUP = "dit_cache_warmup"
internal const val KEY_DIT_CACHE_FN = "dit_cache_fn"
internal const val KEY_DIT_CACHE_BN = "dit_cache_bn"
internal const val KEY_DIT_CACHE_MAX_CACHED = "dit_cache_max_cached"
internal const val KEY_DIT_CACHE_MAX_CONTINUOUS = "dit_cache_max_continuous"
internal const val KEY_DIT_CACHE_DERIVATIVES = "dit_cache_derivatives"
internal const val KEY_DIT_CACHE_INTERVAL = "dit_cache_interval"
internal const val KEY_DIT_CACHE_SCM_MASK = "dit_cache_scm_mask"
internal const val KEY_DIT_CACHE_SCM_DYNAMIC = "dit_cache_scm_dynamic"
internal const val KEY_DIT_SPECTRUM_WARMUP = "dit_spectrum_warmup"
internal const val KEY_DIT_SPECTRUM_WINDOW = "dit_spectrum_window"
internal const val KEY_DIT_SPECTRUM_M = "dit_spectrum_m"
internal const val KEY_DIT_SPECTRUM_W = "dit_spectrum_w"
internal const val KEY_DIT_SPECTRUM_LAM = "dit_spectrum_lam"
internal const val KEY_DIT_SPECTRUM_FLEX = "dit_spectrum_flex"
internal const val KEY_DIT_SPECTRUM_STOP = "dit_spectrum_stop"
internal const val DEFAULT_DIT_EASYCACHE_THRESHOLD = "0.2"
internal const val DEFAULT_DIT_EASYCACHE_START = "0.15"
internal const val DEFAULT_DIT_EASYCACHE_END = "0.95"
internal const val DEFAULT_DIT_CACHE_THRESHOLD = "0.08"
internal const val DEFAULT_DIT_CACHE_WARMUP = "8"
internal const val DEFAULT_DIT_CACHE_FN = "8"
internal const val DEFAULT_DIT_CACHE_BN = "0"
internal const val DEFAULT_DIT_CACHE_MAX_CACHED = "-1"
internal const val DEFAULT_DIT_CACHE_MAX_CONTINUOUS = "-1"
internal const val DEFAULT_DIT_CACHE_DERIVATIVES = "1"
internal const val DEFAULT_DIT_CACHE_INTERVAL = "1"
internal const val DEFAULT_DIT_SPECTRUM_WARMUP = "4"
internal const val DEFAULT_DIT_SPECTRUM_WINDOW = "2"
internal const val DEFAULT_DIT_SPECTRUM_M = "3"
internal const val DEFAULT_DIT_SPECTRUM_W = "0.4"
internal const val DEFAULT_DIT_SPECTRUM_LAM = "1.0"
internal const val DEFAULT_DIT_SPECTRUM_FLEX = "0.5"
internal const val DEFAULT_DIT_SPECTRUM_STOP = "0.9"
internal const val KEY_ORIENTATION = "orientation"
internal const val KEY_CUSTOM_WIDTH = "custom_width"
internal const val KEY_CUSTOM_HEIGHT = "custom_height"
internal const val KEY_DENOISING = "denoising"
internal const val KEY_UPSCALER_STYLE = "upscaler_style"
internal const val KEY_REDRAW = "redraw"
internal const val DEFAULT_STEPS = "8"
internal const val DEFAULT_CFG = "1"
internal const val DEFAULT_SEED = "0"

internal fun loadAuraModelSettings(
    preferences: SharedPreferences,
    model: AuraModel,
    current: AuraState,
): AuraState {
    val orientation = model.savedOrientation(preferences)
    val defaultSize = model.size(orientation)
    val rawW = preferences.getString("model.${model.file.name}.$KEY_CUSTOM_WIDTH", null)
    val rawH = preferences.getString("model.${model.file.name}.$KEY_CUSTOM_HEIGHT", null)
    val customW = rawW ?: defaultSize.first.toString()
    val customH = rawH ?: defaultSize.second.toString()
    val isDit = model.type == SdModelType.DIT
    val defaultSampler = if (isDit) SamplerType.EULER.name else SamplerType.LCM.name
    val defaultSchedule = if (isDit) Schedule.BETA.name else Schedule.KARRAS.name
    val prefix = "model.${model.file.name}"
    return current.copy(
        selectedModelPath = model.file.path,
        promptPrefix = preferences.getString("$prefix.$KEY_PROMPT_PREFIX", "") ?: "",
        steps = preferences.getString("$prefix.$KEY_STEPS", DEFAULT_STEPS) ?: DEFAULT_STEPS,
        cfg = preferences.getString("$prefix.$KEY_CFG", DEFAULT_CFG) ?: DEFAULT_CFG,
        guidance = preferences.getString("$prefix.$KEY_DISTILLED_GUIDANCE", DEFAULT_DISTILLED_GUIDANCE) ?: DEFAULT_DISTILLED_GUIDANCE,
        sampler = preferences.getString("$prefix.$KEY_SAMPLER", defaultSampler) ?: defaultSampler,
        schedule = preferences.getString("$prefix.$KEY_SCHEDULE", defaultSchedule) ?: defaultSchedule,
        backend = preferences.getString(
            "$prefix.$KEY_BACKEND",
            if (model.type == SdModelType.DIT) DitBackend.AUTOMATIC.name else MnnBackend.AUTOMATIC.name,
        ) ?: if (model.type == SdModelType.DIT) DitBackend.AUTOMATIC.name else MnnBackend.AUTOMATIC.name,
        memoryPolicy = preferences.getString("$prefix.$KEY_MEMORY_POLICY", MnnMemoryPolicy.LOW_MEMORY.name) ?: MnnMemoryPolicy.LOW_MEMORY.name,
        seed = preferences.getString("$prefix.$KEY_SEED", DEFAULT_SEED) ?: DEFAULT_SEED,
        seedLocked = preferences.getBoolean("$prefix.$KEY_SEED_LOCKED", false),
        vPred = preferences.getBoolean("$prefix.$KEY_V_PRED", SdModel.hasVPredMarker(model.file.path)),
        imageRefine = preferences.getBoolean("$prefix.$KEY_IMAGE_REFINE", false),
        imageRefineStrength = preferences.getFloat("$prefix.$KEY_IMAGE_REFINE_STRENGTH", 0.20f),
        imageRefinePrompt = preferences.getString(
            "$prefix.$KEY_IMAGE_REFINE_PROMPT", DEFAULT_IMAGE_REFINE_PROMPT,
        ).orEmpty(),
        ditCacheMode = DitCacheMode.fromName(preferences.getString("$prefix.$KEY_DIT_CACHE_MODE", DitCacheMode.DISABLED.name)).name,
        ditEasycacheThreshold = preferences.getString("$prefix.$KEY_DIT_EASYCACHE_THRESHOLD", DEFAULT_DIT_EASYCACHE_THRESHOLD) ?: DEFAULT_DIT_EASYCACHE_THRESHOLD,
        ditEasycacheStart = preferences.getString("$prefix.$KEY_DIT_EASYCACHE_START", DEFAULT_DIT_EASYCACHE_START) ?: DEFAULT_DIT_EASYCACHE_START,
        ditEasycacheEnd = preferences.getString("$prefix.$KEY_DIT_EASYCACHE_END", DEFAULT_DIT_EASYCACHE_END) ?: DEFAULT_DIT_EASYCACHE_END,
        ditCacheThreshold = preferences.getString("$prefix.$KEY_DIT_CACHE_THRESHOLD", DEFAULT_DIT_CACHE_THRESHOLD) ?: DEFAULT_DIT_CACHE_THRESHOLD,
        ditCacheWarmup = preferences.getString("$prefix.$KEY_DIT_CACHE_WARMUP", DEFAULT_DIT_CACHE_WARMUP) ?: DEFAULT_DIT_CACHE_WARMUP,
        ditCacheFn = preferences.getString("$prefix.$KEY_DIT_CACHE_FN", DEFAULT_DIT_CACHE_FN) ?: DEFAULT_DIT_CACHE_FN,
        ditCacheBn = preferences.getString("$prefix.$KEY_DIT_CACHE_BN", DEFAULT_DIT_CACHE_BN) ?: DEFAULT_DIT_CACHE_BN,
        ditCacheMaxCached = preferences.getString("$prefix.$KEY_DIT_CACHE_MAX_CACHED", DEFAULT_DIT_CACHE_MAX_CACHED) ?: DEFAULT_DIT_CACHE_MAX_CACHED,
        ditCacheMaxContinuous = preferences.getString("$prefix.$KEY_DIT_CACHE_MAX_CONTINUOUS", DEFAULT_DIT_CACHE_MAX_CONTINUOUS) ?: DEFAULT_DIT_CACHE_MAX_CONTINUOUS,
        ditCacheDerivatives = preferences.getString("$prefix.$KEY_DIT_CACHE_DERIVATIVES", DEFAULT_DIT_CACHE_DERIVATIVES) ?: DEFAULT_DIT_CACHE_DERIVATIVES,
        ditCacheInterval = preferences.getString("$prefix.$KEY_DIT_CACHE_INTERVAL", DEFAULT_DIT_CACHE_INTERVAL) ?: DEFAULT_DIT_CACHE_INTERVAL,
        ditCacheScmMask = preferences.getString("$prefix.$KEY_DIT_CACHE_SCM_MASK", "") ?: "",
        ditCacheScmDynamic = preferences.getBoolean("$prefix.$KEY_DIT_CACHE_SCM_DYNAMIC", true),
        ditSpectrumWarmup = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_WARMUP", DEFAULT_DIT_SPECTRUM_WARMUP) ?: DEFAULT_DIT_SPECTRUM_WARMUP,
        ditSpectrumWindow = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_WINDOW", DEFAULT_DIT_SPECTRUM_WINDOW) ?: DEFAULT_DIT_SPECTRUM_WINDOW,
        ditSpectrumM = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_M", DEFAULT_DIT_SPECTRUM_M) ?: DEFAULT_DIT_SPECTRUM_M,
        ditSpectrumW = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_W", DEFAULT_DIT_SPECTRUM_W) ?: DEFAULT_DIT_SPECTRUM_W,
        ditSpectrumLam = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_LAM", DEFAULT_DIT_SPECTRUM_LAM) ?: DEFAULT_DIT_SPECTRUM_LAM,
        ditSpectrumFlex = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_FLEX", DEFAULT_DIT_SPECTRUM_FLEX) ?: DEFAULT_DIT_SPECTRUM_FLEX,
        ditSpectrumStop = preferences.getString("$prefix.$KEY_DIT_SPECTRUM_STOP", DEFAULT_DIT_SPECTRUM_STOP) ?: DEFAULT_DIT_SPECTRUM_STOP,
        orientation = orientation,
        customWidth = customW,
        customHeight = customH,
        denoising = preferences.getFloat("$prefix.$KEY_DENOISING", 0.75f),
        upscalerStyle = runCatching {
            UpscaleStyle.valueOf(
                preferences.getString("$prefix.$KEY_UPSCALER_STYLE", UpscaleStyle.PHOTO.name) ?: UpscaleStyle.PHOTO.name,
            )
        }.getOrDefault(UpscaleStyle.PHOTO),
        redrawStrength = preferences.getFloat("$prefix.$KEY_REDRAW", 0f),
        tokenCount = null,
        error = null,
    )
}

internal fun installedModels(context: Context): List<AuraModel> = SdModel.list(context).mapNotNull { file ->
    runCatching {
        val packageType = SdModel.packageType(file)
        AuraModel(
            file = file,
            type = packageType.model,
            runtime = packageType.runtime,
            chunkLimit = promptChunkLimit(file, packageType.model, packageType.runtime),
            external = File(file, ".link").isFile,
        )
    }.getOrNull()
}

private fun promptChunkLimit(
    model: File,
    type: SdModelType,
    runtime: SdRuntimeType,
): Int? {
    if (type == SdModelType.DIT) return 8
    if (runtime == SdRuntimeType.MNN) return null
    if (type != SdModelType.SDXL) return 1
    val has154 = File(model, "154.patch").length() > 0L
    if (
        File(model, "qnn_context.txt").length() > 0L ||
        has154 && File(model, "231.patch").length() > 0L
    ) return 3
    return if (has154) 2 else 1
}

internal data class AuraState(
    val engine: AuraEngine = AuraEngine.LOCAL,
    val lanAddress: String = "",
    val remote: AuraRemoteSettings = AuraRemoteSettings(),
    val remoteCatalog: WebUiCatalog? = null,
    val models: List<AuraModel> = emptyList(),
    val selectedModelPath: String? = null,
    val ditBaseInstalled: Boolean = false,
    val qwenComponentsInstalled: Boolean = false,
    val qwen3EncoderInstalled: Boolean = false,
    val kreaComponentsInstalled: Boolean = false,
    val chromaComponentsInstalled: Boolean = false,
    val mossComponentsInstalled: Boolean = false,
    val animaComponentsInstalled: Boolean = false,
    val promptPrefix: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: String = DEFAULT_STEPS,
    val cfg: String = DEFAULT_CFG,
    val guidance: String = DEFAULT_DISTILLED_GUIDANCE,
    val sampler: String = SamplerType.LCM.name,
    val schedule: String = Schedule.KARRAS.name,
    val backend: String = MnnBackend.AUTOMATIC.name,
    val memoryPolicy: String = MnnMemoryPolicy.LOW_MEMORY.name,
    val seed: String = DEFAULT_SEED,
    val seedLocked: Boolean = false,
    val vPred: Boolean = false,
    val imageRefine: Boolean = false,
    val imageRefineStrength: Float = 0.20f,
    val imageRefinePrompt: String = DEFAULT_IMAGE_REFINE_PROMPT,
    val ditCacheMode: String = DitCacheMode.DISABLED.name,
    val ditEasycacheThreshold: String = DEFAULT_DIT_EASYCACHE_THRESHOLD,
    val ditEasycacheStart: String = DEFAULT_DIT_EASYCACHE_START,
    val ditEasycacheEnd: String = DEFAULT_DIT_EASYCACHE_END,
    val ditCacheThreshold: String = DEFAULT_DIT_CACHE_THRESHOLD,
    val ditCacheWarmup: String = DEFAULT_DIT_CACHE_WARMUP,
    val ditCacheFn: String = DEFAULT_DIT_CACHE_FN,
    val ditCacheBn: String = DEFAULT_DIT_CACHE_BN,
    val ditCacheMaxCached: String = DEFAULT_DIT_CACHE_MAX_CACHED,
    val ditCacheMaxContinuous: String = DEFAULT_DIT_CACHE_MAX_CONTINUOUS,
    val ditCacheDerivatives: String = DEFAULT_DIT_CACHE_DERIVATIVES,
    val ditCacheInterval: String = DEFAULT_DIT_CACHE_INTERVAL,
    val ditCacheScmMask: String = "",
    val ditCacheScmDynamic: Boolean = true,
    val ditSpectrumWarmup: String = DEFAULT_DIT_SPECTRUM_WARMUP,
    val ditSpectrumWindow: String = DEFAULT_DIT_SPECTRUM_WINDOW,
    val ditSpectrumM: String = DEFAULT_DIT_SPECTRUM_M,
    val ditSpectrumW: String = DEFAULT_DIT_SPECTRUM_W,
    val ditSpectrumLam: String = DEFAULT_DIT_SPECTRUM_LAM,
    val ditSpectrumFlex: String = DEFAULT_DIT_SPECTRUM_FLEX,
    val ditSpectrumStop: String = DEFAULT_DIT_SPECTRUM_STOP,
    val orientation: AuraOrientation = AuraOrientation.SQUARE,
    val customWidth: String = "512",
    val customHeight: String = "512",
    val denoising: Float = 0.75f,
    val sourcePath: String? = null,
    val sourcePreview: Bitmap? = null,
    val tokenCount: Int? = null,
    val operation: String? = null,
    val operationProgress: Float? = null,
    val generating: Boolean = false,
    val progress: Int = 0,
    val livePreview: Bitmap? = null,
    val result: Bitmap? = null,
    val resultPath: String? = null,
    val resultMetadata: AuraImageMetadata? = null,
    val upscalerStyle: UpscaleStyle = UpscaleStyle.PHOTO,
    val redrawStrength: Float = 0f,
    val upscalerRevision: Int = 0,
    val error: String? = null,
) {
    val hasGenerationModel: Boolean get() = when (engine) {
        AuraEngine.LOCAL -> selectedModel != null
        AuraEngine.WEBUI, AuraEngine.LOCAL_DREAM -> true
        AuraEngine.LAN -> validAuraLanAddress(lanAddress)
    }

    val selectedModel: AuraModel?
        get() = models.firstOrNull { it.file.path == selectedModelPath }

    val isDit: Boolean get() = selectedModel?.type == SdModelType.DIT

    val enhancementAvailability: EnhancementAvailability
        get() = when {
            engine == AuraEngine.LAN -> EnhancementAvailability.LAN
            engine == AuraEngine.LOCAL_DREAM -> EnhancementAvailability.LOCAL_DREAM
            result == null -> EnhancementAvailability.NO_IMAGE
            resultMetadata?.enhanced == true -> EnhancementAvailability.ALREADY_ENHANCED
            engine != AuraEngine.LOCAL -> EnhancementAvailability.READY
            resultMetadata?.modelType == SdModelType.SDXL.name -> EnhancementAvailability.SDXL
            resultMetadata == null && selectedModel?.type == SdModelType.SDXL -> EnhancementAvailability.SDXL
            (result.width to result.height) !in UpscalerModels.SOURCE_SIZES -> EnhancementAvailability.UNSUPPORTED_SIZE
            else -> EnhancementAvailability.READY
        }
}

internal data class AuraModel(
    val file: File,
    val type: SdModelType,
    val runtime: SdRuntimeType,
    val orientations: List<AuraOrientation> = when {
        type == SdModelType.DIT || type == SdModelType.SDXL || runtime == SdRuntimeType.MNN -> listOf(AuraOrientation.SQUARE)
        else -> AuraSizes.available(file)
    },
    val chunkLimit: Int?,
    val external: Boolean = false,
) {
    fun savedOrientation(preferences: SharedPreferences): AuraOrientation {
        val saved = runCatching {
            preferences.getString("model.${file.name}.$KEY_ORIENTATION", null)
        }.getOrNull()
        return orientations.firstOrNull { it.name == saved }
            ?: orientations.firstOrNull()
            ?: AuraOrientation.SQUARE
    }

    fun size(orientation: AuraOrientation): Pair<Int, Int> = when {
        type == SdModelType.DIT -> 512 to 512
        type == SdModelType.SDXL -> 1024 to 1024
        runtime == SdRuntimeType.MNN -> 512 to 512
        orientation in orientations -> orientation.width to orientation.height
        else -> 512 to 512
    }
}

internal data class WebUiCatalog(
    val models: List<String>,
    val samplers: List<String>,
    val schedulers: List<Pair<String, String>>,
    val upscalers: List<String>,
    val img2img: Boolean,
    val taskProgress: Boolean,
) {
    fun toJson(): String = buildJsonObject {
        put("models", JsonArray(models.map(::JsonPrimitive)))
        put("samplers", JsonArray(samplers.map(::JsonPrimitive)))
        put("schedulers", JsonArray(schedulers.map { (name, label) -> buildJsonArray { add(name); add(label) } }))
        put("upscalers", JsonArray(upscalers.map(::JsonPrimitive)))
        put("img2img", img2img)
        put("taskProgress", taskProgress)
    }.toString()

    companion object {
        fun fromJson(text: String): WebUiCatalog {
            val json = Json.parseToJsonElement(text).jsonObject
            fun strings(key: String) = json[key]!!.jsonArray.let { array -> List(array.size) { array[it].jsonPrimitive.content } }
            val schedulers = json["schedulers"]!!.jsonArray
            return WebUiCatalog(
                models = strings("models"),
                samplers = strings("samplers"),
                schedulers = List(schedulers.size) {
                    schedulers[it].jsonArray.let { pair -> pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content }
                },
                upscalers = strings("upscalers"),
                img2img = json["img2img"]!!.jsonPrimitive.boolean,
                taskProgress = json["taskProgress"]!!.jsonPrimitive.boolean,
            )
        }
    }
}

internal enum class EnhancementAvailability(val message: Int) {
    LAN(R.string.aura_lan_text_only),
    LOCAL_DREAM(R.string.aura_local_dream_enhance_note),
    READY(R.string.aura_enhance_note),
    NO_IMAGE(R.string.aura_enhance_requires_image),
    SDXL(R.string.aura_enhance_sdxl_disabled),
    ALREADY_ENHANCED(R.string.aura_enhance_once_only),
    UNSUPPORTED_SIZE(R.string.aura_enhance_unsupported_size),
}
