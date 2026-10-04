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
        if (runtime == AuraEngine.LOCAL_DREAM.name) R.string.aura_local_dream else if (isRemote) R.string.aura_webui else if (runtime == SdRuntimeType.MNN.name) R.string.engines_mnn else R.string.aura_runtime_qnn,
    )
    val processorName = context.getString(
        if (runtime == AuraEngine.LOCAL_DREAM.name) R.string.aura_local_dream else if (isRemote) R.string.aura_webui else if (runtime == SdRuntimeType.MNN.name) MnnBackend.fromName(backend).label() else R.string.aura_runtime_qnn,
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
    override val seed: Long,
    val sampler: String,
    val schedule: String,
    val vPred: Boolean,
    val backend: String,
    val memoryPolicy: String,
    val imageRefine: Boolean,
    val imageRefineStrength: Float,
    val imageRefinePrompt: String,
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
    val modelInfo = AuraModel(model, packageType.model, packageType.runtime, chunkLimit = null)
    val (width, height) = resolveDimensions(preferences, model, modelInfo, packageType.model == SdModelType.DIT)
    val seed = resolveSeed(preferences, model, rollSeed)
    val macros = MacroBus(context)
    val prompt = macros.text(if (includePromptPrefix) studioPrompt(preferences.getString("model.${model.name}.$KEY_PROMPT_PREFIX", "").orEmpty(), imagePrompt) else imagePrompt)
    val negative = macros.text(preferences.getString(KEY_NEGATIVE_PROMPT, "").orEmpty().trim())
    val sampler = preferences.getString("model.${model.name}.$KEY_SAMPLER", SamplerType.LCM.name) ?: SamplerType.LCM.name
    val schedule = preferences.getString("model.${model.name}.$KEY_SCHEDULE", Schedule.KARRAS.name) ?: Schedule.KARRAS.name
    val backend = preferences.getString("model.${model.name}.$KEY_BACKEND", MnnBackend.AUTOMATIC.name) ?: MnnBackend.AUTOMATIC.name
    val memoryPolicy = preferences.getString("model.${model.name}.$KEY_MEMORY_POLICY", MnnMemoryPolicy.LOW_MEMORY.name) ?: MnnMemoryPolicy.LOW_MEMORY.name
    val metadata = AuraImageMetadata(
        prompt = prompt,
        negative = negative,
        seed = seed,
        steps = steps,
        cfg = cfg,
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
    )
    return AuraLocalGenerationConfig(
        modelPath = model.path,
        prompt = prompt,
        negativePrompt = negative,
        width = width,
        height = height,
        steps = steps,
        cfg = cfg,
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
internal const val KEY_ORIENTATION = "orientation"
internal const val KEY_CUSTOM_WIDTH = "custom_width"
internal const val KEY_CUSTOM_HEIGHT = "custom_height"
internal const val KEY_DENOISING = "denoising"
internal const val KEY_UPSCALER_STYLE = "upscaler_style"
internal const val KEY_REDRAW = "redraw"
internal const val DEFAULT_STEPS = "8"
internal const val DEFAULT_CFG = "1"
internal const val DEFAULT_SEED = "0"
internal const val LORA_DIRECTORY = "sd_loras"

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
    return current.copy(
        selectedModelPath = model.file.path,
        promptPrefix = preferences.getString("model.${model.file.name}.$KEY_PROMPT_PREFIX", "") ?: "",
        steps = preferences.getString("model.${model.file.name}.$KEY_STEPS", DEFAULT_STEPS) ?: DEFAULT_STEPS,
        cfg = preferences.getString("model.${model.file.name}.$KEY_CFG", DEFAULT_CFG) ?: DEFAULT_CFG,
        sampler = preferences.getString("model.${model.file.name}.$KEY_SAMPLER", SamplerType.LCM.name) ?: SamplerType.LCM.name,
        schedule = preferences.getString("model.${model.file.name}.$KEY_SCHEDULE", Schedule.KARRAS.name) ?: Schedule.KARRAS.name,
        backend = preferences.getString("model.${model.file.name}.$KEY_BACKEND", MnnBackend.AUTOMATIC.name) ?: MnnBackend.AUTOMATIC.name,
        memoryPolicy = preferences.getString("model.${model.file.name}.$KEY_MEMORY_POLICY", MnnMemoryPolicy.LOW_MEMORY.name) ?: MnnMemoryPolicy.LOW_MEMORY.name,
        seed = preferences.getString("model.${model.file.name}.$KEY_SEED", DEFAULT_SEED) ?: DEFAULT_SEED,
        seedLocked = preferences.getBoolean("model.${model.file.name}.$KEY_SEED_LOCKED", false),
        vPred = preferences.getBoolean("model.${model.file.name}.$KEY_V_PRED", SdModel.hasVPredMarker(model.file.path)),
        imageRefine = preferences.getBoolean("model.${model.file.name}.$KEY_IMAGE_REFINE", false),
        imageRefineStrength = preferences.getFloat("model.${model.file.name}.$KEY_IMAGE_REFINE_STRENGTH", 0.20f),
        imageRefinePrompt = preferences.getString(
            "model.${model.file.name}.$KEY_IMAGE_REFINE_PROMPT", DEFAULT_IMAGE_REFINE_PROMPT,
        ).orEmpty(),
        orientation = orientation,
        customWidth = customW,
        customHeight = customH,
        denoising = preferences.getFloat("model.${model.file.name}.$KEY_DENOISING", 0.75f),
        upscalerStyle = runCatching {
            UpscaleStyle.valueOf(
                preferences.getString("model.${model.file.name}.$KEY_UPSCALER_STYLE", UpscaleStyle.PHOTO.name) ?: UpscaleStyle.PHOTO.name,
            )
        }.getOrDefault(UpscaleStyle.PHOTO),
        redrawStrength = preferences.getFloat("model.${model.file.name}.$KEY_REDRAW", 0f),
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
    val promptPrefix: String = "",
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: String = DEFAULT_STEPS,
    val cfg: String = DEFAULT_CFG,
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
    val orientation: AuraOrientation = AuraOrientation.SQUARE,
    val customWidth: String = "1024",
    val customHeight: String = "1024",
    val denoising: Float = 0.75f,
    val sourcePath: String? = null,
    val sourcePreview: Bitmap? = null,
    val tokenCount: Int? = null,
    val operation: String? = null,
    val operationProgress: Float? = null,
    val generating: Boolean = false,
    val progress: Int = 0,
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
        type == SdModelType.DIT || type == SdModelType.SDXL -> 1024 to 1024
        runtime == SdRuntimeType.MNN -> 512 to 512
        orientation in orientations -> orientation.width to orientation.height
        else -> 512 to 512
    }
}

internal data class AuraLora(val file: File, val strength: Float)

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
