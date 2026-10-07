package com.mrj.fancyai.ui.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mrj.fancyai.R

internal enum class HomeCategory(
    @param:StringRes val nameRes: Int,
) {
    People(R.string.home_category_people),
    Social(R.string.label_social),
    Tools(R.string.home_category_tools),
    Play(R.string.home_category_play),
    System(R.string.home_category_system),
}

internal data class HomeApp(
    val id: String,
    @param:StringRes val name: Int,
    @param:DrawableRes val icon: Int,
    val category: HomeCategory,
    val destination: HomeDestination,
)

internal val HomeApps = listOf(
    HomeApp("terminal", R.string.terminal_title, R.drawable.home_icon_terminal, HomeCategory.Tools, HomeDestination.Terminal),
    HomeApp("chat", R.string.home_app_chat, R.drawable.home_icon_chat, HomeCategory.People, HomeDestination.Chat),
    HomeApp("characters", R.string.characters_title, R.drawable.home_icon_character, HomeCategory.People, HomeDestination.Characters),
    HomeApp("binder", R.string.home_app_binder, R.drawable.home_icon_binder, HomeCategory.Social, HomeDestination.Binder),
    HomeApp("phone", R.string.home_app_phone, R.drawable.home_icon_phone, HomeCategory.People, HomeDestination.Phone),
    HomeApp("groups", R.string.home_app_groups, R.drawable.home_icon_groups, HomeCategory.People, HomeDestination.Groups),
    HomeApp(
        "root-creator",
        R.string.home_app_root_creator,
        R.drawable.home_icon_creator,
        HomeCategory.People,
        HomeDestination.RootCreator,
    ),
    HomeApp("automatic-posts", R.string.automatic_posts_title, R.drawable.home_icon_automatic_posts, HomeCategory.Social, HomeDestination.AutomaticPosts),
    HomeApp("y", R.string.home_app_y, R.drawable.home_icon_y, HomeCategory.Social, HomeDestination.Y),
    HomeApp(
        "ustagram",
        R.string.home_app_ustagram,
        R.drawable.home_icon_ustagram,
        HomeCategory.Social,
        HomeDestination.Ustagram,
    ),
    HomeApp(
        "rebbit",
        R.string.home_app_rebbit,
        R.drawable.home_icon_rebbit,
        HomeCategory.Social,
        HomeDestination.Rebbit,
    ),
    HomeApp(
        "dare",
        R.string.home_app_dare,
        R.drawable.home_icon_dare,
        HomeCategory.Social,
        HomeDestination.Dare,
    ),
    HomeApp(
        "aura",
        R.string.home_app_aura,
        R.drawable.home_icon_aura,
        HomeCategory.Tools,
        HomeDestination.Aura,
    ),
    HomeApp(
        "aura_converter",
        R.string.home_app_aura_converter,
        R.drawable.home_icon_aura,
        HomeCategory.Tools,
        HomeDestination.AuraConverter,
    ),
    HomeApp(
        "aura-swap",
        R.string.home_app_aura_swap,
        R.drawable.ic_swap,
        HomeCategory.Tools,
        HomeDestination.AuraSwap,
    ),
    HomeApp(
        "vision",
        R.string.home_app_vision,
        R.drawable.home_icon_vision,
        HomeCategory.Tools,
        HomeDestination.Vision,
    ),
    HomeApp(
        "gallery",
        R.string.home_app_gallery,
        R.drawable.home_icon_gallery,
        HomeCategory.Tools,
        HomeDestination.Gallery,
    ),
    HomeApp(
        "root-producer",
        R.string.home_app_root_producer,
        R.drawable.home_icon_producer,
        HomeCategory.Tools,
        HomeDestination.RootProducer,
    ),
    HomeApp("games", R.string.home_app_games, R.drawable.home_icon_games, HomeCategory.Play, HomeDestination.Games),
    HomeApp("cleanup", R.string.cleanup_title, R.drawable.home_icon_cleanup, HomeCategory.System, HomeDestination.Cleanup),
    HomeApp(
        "storage",
        R.string.home_app_storage,
        R.drawable.home_icon_storage,
        HomeCategory.System,
        HomeDestination.FileManager,
    ),
    HomeApp(
        "benchmark",
        R.string.benchmark_title,
        R.drawable.home_icon_benchmark,
        HomeCategory.System,
        HomeDestination.Benchmark,
    ),
    HomeApp(
        "settings",
        R.string.settings_title,
        R.drawable.home_icon_settings,
        HomeCategory.System,
        HomeDestination.Settings,
    ),
    HomeApp(
        "lorebook",
        R.string.home_app_lorebook,
        R.drawable.home_icon_lorebook,
        HomeCategory.System,
        HomeDestination.Lorebook,
    ),
    HomeApp("memory", R.string.memory_library_title, R.drawable.home_icon_memory, HomeCategory.System, HomeDestination.MemoryLibrary),
)

internal val DockApps = listOf("chat", "characters", "gallery", "settings").map { id ->
    checkNotNull(HomeApps.find { it.id == id })
}
