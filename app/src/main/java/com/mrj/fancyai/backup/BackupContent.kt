package com.mrj.fancyai.backup

/** Saved content and preference types used by the existing app readers. */
internal object BackupContent {
    val roots = setOf("characters", "chats", "chat_memory", "chat_attachments", "character_draft",
        "user", "user_draft", "groups", "rebbit", "ustagram", "y", "dare", "media", "music", "phone-history",
        "lorebook.json", "voice/voices", "benchmark")

    fun safePath(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
        '\\' !in path && '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    fun file(path: String): Boolean = safePath(path) && roots.any { path == it || path.startsWith("$it/") } &&
        path.split('/').none { it.startsWith('.') } && !path.endsWith(".bak") && !path.endsWith(".new")

    private val strings = mapOf(
        "assistant_protocol" to "instruction requested_image_instruction image_triggers",
        "user_profile" to "name handle description appearance",
        "engine" to "cloud_base_url active_provider",
        "user_profile_draft" to "name handle description appearance",
        "root_creator" to "idea thought_process", "root_producer" to "idea title brief lyrics tier thought_process",
        "phone" to "search", "vision" to "prompt", "character_search_draft" to "search",
        "group_settings" to "instruction",
        "group_create_draft" to "name scenario members",
        "binder_settings" to "interest style nationality traits custom_trait",
        "ustagram_settings" to "prompt custom_themes disabled_themes theme_draft theme_filter",
        "rebbit_settings" to "prompt communities disabled_communities community_draft community_filter community_search",
        "y_settings" to "prompt",
        "dare_settings" to "prompt",
        "aura" to "prompt_prefix_draft prompt_draft negative_prompt_draft image_engine lan_address",
        "voice" to "tts_text supertonic_language voice_draft_name tts_provider stt_provider",
    ).mapValues { it.value.split(' ').toSet() }
    private val booleans = mapOf(
        "user_profile_draft" to "started remove.avatar", "binder_settings" to "configured",
        "root_producer" to "plan_ready",
        "lorebook" to "enabled", "chat_memory" to "recallLatest",
        "voice" to "playback_skip_actions", "automatic_social_posts" to "enabled",
    ).mapValues { it.value.split(' ').toSet() }
    private val integers = mapOf("binder_settings" to "minimum_age maximum_age", "chat_memory" to "maxInjections",
        "system_prompts" to "prompt_count selected_prompt", "voice" to "playback_characters",
        "automatic_social_posts" to "minutes").mapValues { it.value.split(' ').toSet() }
    private val drafts = setOf("chat_draft", "chat_rename_draft", "chat_search_draft", "group_message_drafts",
        "game_draft", "game_instructions", "lorebook_drafts", "chat_memory_drafts")
    private val generation = setOf("generation", "generation_advanced", "generation_cloud")
    val preferenceNames = strings.keys + booleans.keys + integers.keys + drafts +
        setOf("appearance_settings", "system_prompt_draft") + generation + generation.map { "${it}_memory" }

    private val fieldTypes: Map<String, Map<Pair<String, String?>, Set<String>>> = mapOf(
        "string" to strings.mapKeys { it.key to null } + mapOf(
            ("aura" to "remote.WEBUI.") to setOf("url", "username", "password", "catalog", "model", "steps", "cfg", "width", "height",
                "sampler", "scheduler", "seed", "prefix", "upscaler"),
            ("aura" to "model.") to setOf("steps", "cfg", "sampler", "schedule", "backend", "memory_policy", "seed", "orientation", "upscaler_style", "image_refine_prompt"),
            ("character_creator_draft." to ".") to setOf("character_id", "draft_json"),
        ),
        "boolean" to booleans.mapKeys { it.key to null } + mapOf(
            ("aura" to "remote.WEBUI.") to setOf("seed_locked"),
            ("aura" to "model.") to setOf("seed_locked", "v_pred", "image_refine", "redraw"),
            ("character_creator_draft." to ".") to setOf("remove.avatar.webp", "remove.background.webp"),
        ),
        "int" to integers.mapKeys { it.key to null } + (("generation" to "@") to setOf("top_k", "output_tokens", "penalty_window", "no_repeat_ngram", "no_repeat_window")),
        "float" to mapOf(
            ("chat_memory" to null) to setOf("minimumScore"),
            ("generation" to "@") to setOf("temperature", "dynamic_temperature", "top_p", "min_p", "repetition_penalty", "presence_penalty", "frequency_penalty"),
            ("aura" to "remote.WEBUI.") to setOf("denoising", "redraw"),
            ("aura" to "model.") to setOf("image_refine_strength", "denoising"),
        ),
        "string_set" to mapOf(("aura" to null) to setOf("webui_addresses")),
    )
    private val prefixTypes = mapOf(
        "string" to mapOf(
            "engine" to listOf("cloud_api_key@", "cloud_model@", "cloud_query@"),
            "voice" to listOf("character_voice@", "voice_name@"),
            "rebbit_settings" to listOf("comment_draft_"),
            "ustagram_settings" to listOf("comment_draft_"), "y_settings" to listOf("comment_draft_"),
        ),
        "string_set" to mapOf("engine" to listOf("cloud_supported_parameters@")),
    )
    private val booleanSuffixes = mapOf("chat_memory" to listOf(".enabled", ".autoCollect"))
    private val scopes = mapOf("aura" to listOf("remote.WEBUI.", "model."))
    private val stringPatterns = mapOf(
        "voice" to Regex("(tts|stt)_(model|query|voice)@.+"),
        "system_prompts" to Regex("(title|instruction|template)_[0-9]+"),
        "system_prompt_draft" to Regex("(title|instruction)_[0-9]+"),
    )

    /** Expected types prevent an edited or newer value crashing a SharedPreferences getter. */
    fun type(name: String, key: String): String? {
        if (name in setOf("pro_usage", "play_pro", "github_pro", "github_pro_draft") ||
            key == "is_pro" || listOf("license_", "billing_", "purchase_", "entitlement_").any(key::startsWith) ||
            (key.startsWith("free_") && key.endsWith("_used"))) return null
        if (name in drafts) return "string" // These stores contain text only, keyed by content IDs.
        if (name.removeSuffix("_memory") in generation && key == "history_limit") return "int"
        prefixTypes.entries.firstOrNull { (_, stores) -> stores[name].orEmpty().any(key::startsWith) }?.let { return it.key }
        if (stringPatterns[name]?.matches(key) == true) return "string"
        if (booleanSuffixes[name].orEmpty().any(key::endsWith)) return "boolean"
        val scope = scopes[name]?.firstOrNull(key::startsWith)
        val (store, field) = when {
            scope != null -> ("aura" to scope) to key.substringAfterLast('.')
            name in generation -> ("generation" to "@") to key.substringBefore('@')
            name.startsWith("character_creator_draft.") && safePath(name) -> ("character_creator_draft." to ".") to key
            else -> (name to null) to key
        }
        return fieldTypes.entries.firstOrNull { (_, stores) -> field in stores[store].orEmpty() }?.key
    }
}
