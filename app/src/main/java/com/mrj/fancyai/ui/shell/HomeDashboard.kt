package com.mrj.fancyai.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.ui.settings.EngineStatusNames
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.launch

@Composable
internal fun HomeDashboard(
    navigation: HomeNavigationState,
    engineStatus: EngineStatusNames,
    engineLine: String,
    visible: Boolean,
    onRequestExit: () -> Unit,
) {
    val pager = rememberPagerState { HomePageCount }
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(HomeCategory.People) }
    val engineName = engineStatus.active

    if (!visible) return

    BackHandler {
        if (pager.currentPage == RootPageIndex) onRequestExit()
        else scope.launch { pager.animateScrollToPage(RootPageIndex) }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        HomeWallpaper()
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ),
            ) { page ->
                if (page == RootPageIndex) {
                    RootPage(
                        engineName = engineName,
                        engineLine = engineLine,
                        onOpenEngines = { navigation.openEngineSetup(engineStatus.activeCloud, returnHome = true) },
                    ) {
                        navigation.destination.value =
                            if (engineName != null) HomeDestination.RootChat else HomeDestination.Settings
                    }
                } else {
                    AppsPage(
                        category = category,
                        onCategory = { category = it },
                        onOpen = { app ->
                            when (app) {
                                HomeDestination.Binder, HomeDestination.Phone, HomeDestination.Rebbit,
                                HomeDestination.Ustagram, HomeDestination.Y, HomeDestination.Games,
                                HomeDestination.Groups, HomeDestination.RootCreator, HomeDestination.Chat ->
                                    navigation.openWithEngine(app, engineName, engineStatus.activeCloud)
                                HomeDestination.Aura -> {
                                    navigation.auraReturnDestination = HomeDestination.Home
                                    navigation.destination.value = app
                                }
                                else -> navigation.destination.value = app
                            }
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(HomePageCount) { page ->
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .requiredWidth(if (page == pager.currentPage) 20.dp else 6.dp)
                            .height(1.dp)
                            .background(if (page == pager.currentPage) Accent else MaterialTheme.colorScheme.outline),
                    )
                }
            }
            HomeDock(
                visible = pager.currentPage == RootPageIndex,
                onOpenCharacters = { navigation.destination.value = HomeDestination.Characters },
                onOpenGallery = { navigation.destination.value = HomeDestination.Gallery },
                onOpenSettings = { navigation.destination.value = HomeDestination.Settings },
            ) { navigation.openWithEngine(HomeDestination.Chat, engineName, engineStatus.activeCloud) }
        }
    }
}
