package com.mrj.fancyai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mrj.fancyai.ui.screen.HomeScreen
import com.mrj.fancyai.ui.chat.MessengerScreen
import com.mrj.fancyai.ui.settings.SettingsScreen
import com.mrj.fancyai.ui.imaging.ImagingScreen
import com.mrj.fancyai.ui.gallery.GalleryScreen
import com.mrj.fancyai.ui.social.SocialScreen
import com.mrj.fancyai.ui.phone.PhoneScreen
import com.mrj.fancyai.ui.phone.PhoneContactsScreen
import com.mrj.fancyai.ui.games.GamesScreen
import com.mrj.fancyai.ui.chat.MessengerViewModel
import com.mrj.fancyai.ui.imaging.ImagingViewModel
import com.mrj.fancyai.ui.gallery.GalleryViewModel
import com.mrj.fancyai.ui.social.SocialViewModel
import com.mrj.fancyai.ui.phone.PhoneViewModel
import com.mrj.fancyai.ui.phone.PhoneContactsViewModel
import com.mrj.fancyai.ui.settings.SettingsViewModel
import com.mrj.fancyai.ui.games.GamesViewModel
import com.mrj.fancyai.di.ServiceLocator
import com.mrj.fancyai.di.viewModelFactory

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(navController = navController)
        }
        
        // Unified Messenger Hub
        composable("messenger") {
            val viewModel: MessengerViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getMessengerViewModel() }
            )
            MessengerScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("imaging") {
            val viewModel: ImagingViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getImagingViewModel() }
            )
            ImagingScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("gallery") {
            val viewModel: GalleryViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getGalleryViewModel() }
            )
            GalleryScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("social/{platform}") { backStackEntry ->
            val platform = backStackEntry.arguments?.getString("platform") ?: "ustagram"
            val viewModel: SocialViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getSocialViewModel(platform) }
            )
            SocialScreen(
                platform = platform,
                navController = navController,
                viewModel = viewModel
            )
        }

        // Phone: pick a contact, then place a voice call.
        composable("phone") {
            val viewModel: PhoneContactsViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getPhoneContactsViewModel() }
            )
            PhoneContactsScreen(
                navController = navController,
                viewModel = viewModel,
                onCall = { charId -> navController.navigate("phone/$charId") }
            )
        }

        composable("phone/{charId}") { backStackEntry ->
            val charId = backStackEntry.arguments?.getString("charId") ?: return@composable
            val viewModel: PhoneViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getPhoneViewModel(charId) }
            )
            PhoneScreen(
                charId = charId,
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("settings") {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getSettingsViewModel() }
            )
            SettingsScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("games") {
            val viewModel: GamesViewModel = viewModel(
                factory = viewModelFactory { ServiceLocator.getGamesViewModel() }
            )
            GamesScreen(
                navController = navController,
                viewModel = viewModel
            )
        }
    }
}
