package id.bayu.mygalleryvault.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.ui.screens.browser.PrivateBrowserScreen
import id.bayu.mygalleryvault.ui.screens.home.HomeScreen
import id.bayu.mygalleryvault.ui.screens.lock.LockScreen
import id.bayu.mygalleryvault.ui.screens.settings.SettingsScreen
import id.bayu.mygalleryvault.ui.screens.setup.SetupScreen
import id.bayu.mygalleryvault.ui.screens.viewer.ImageViewerScreen
import id.bayu.mygalleryvault.ui.screens.viewer.VideoPlayerScreen
import id.bayu.mygalleryvault.ui.theme.SecureVaultTheme

object Routes {
    const val SETUP = "setup"
    const val LOCK = "lock"
    const val HOME = "home"
    const val FOLDER = "folder/{folderId}"
    const val VIEWER = "view/{fileId}"
    const val VIDEO = "video/{fileId}"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"

    fun folder(id: Long) = "folder/$id"
    fun viewer(fileId: Long) = "view/$fileId"
    fun video(fileId: Long) = "video/$fileId"
}

@Composable
fun AppRoot(activity: androidx.fragment.app.FragmentActivity) {
    val app = activity.application as SecureVaultApp
    val navController = rememberNavController()
    val unlocked by VaultSession.isUnlocked.collectAsStateWithLifecycle()
    val vaultCreated = remember { app.container.authRepository.isVaultCreated }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Bottom tabs stay visible while browsing the vault tree; immersive
    // surfaces (viewer/player/settings) take over the whole screen.
    val showBottomBar = unlocked && (
        currentRoute == Routes.HOME ||
            currentRoute == Routes.FOLDER ||
            currentRoute == Routes.BROWSER
        )

    LaunchedEffect(unlocked) {
        if (!unlocked) {
            val current = navController.currentDestination?.route
            if (current != Routes.LOCK && current != Routes.SETUP) {
                navController.navigate(Routes.LOCK) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    SecureVaultTheme {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == Routes.HOME || currentRoute == Routes.FOLDER,
                            onClick = { goTab(navController, Routes.HOME) },
                            icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                            label = { Text("Gallery") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.BROWSER,
                            onClick = { goTab(navController, Routes.BROWSER) },
                            icon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                            label = { Text("Browser") },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = if (vaultCreated) Routes.LOCK else Routes.SETUP,
                modifier = Modifier.padding(padding),
                // Subtle-fluid motion (180-250ms): fade+slide for detail push,
                // pure crossfade when hopping between the two bottom tabs.
                enterTransition = {
                    val from = initialState.destination.route
                    val to = targetState.destination.route
                    val isTabHop =
                        setOf(from, to).intersect(setOf(Routes.HOME, Routes.FOLDER, Routes.BROWSER)) ==
                            setOf(Routes.HOME, Routes.BROWSER) ||
                            (from == Routes.HOME && to == Routes.FOLDER) ||
                            (from == Routes.FOLDER && to == Routes.HOME)
                    if (isTabHop) fadeIn(tween(180))
                    else fadeIn(tween(220)) + androidx.compose.animation.slideInVertically(tween(220)) { it / 24 }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = {
                    fadeOut(tween(180)) +
                        androidx.compose.animation.slideOutVertically(tween(220)) { it / 24 }
                },
            ) {
                composable(Routes.SETUP) {
                    SetupScreen(
                        onSetupComplete = {
                            navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.LOCK) {
                    LockScreen(
                        activity = activity,
                        onUnlocked = {
                            navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.HOME) {
                    HomeScreen(activity = activity, navController = navController, folderId = null)
                }
                composable(
                    route = Routes.FOLDER,
                    arguments = listOf(navArgument("folderId") { type = NavType.LongType }),
                ) { entry ->
                    val folderId = entry.arguments?.getLong("folderId")
                    HomeScreen(activity = activity, navController = navController, folderId = folderId)
                }
                composable(
                    route = Routes.VIEWER,
                    arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
                ) { entry ->
                    val fileId = entry.arguments?.getLong("fileId") ?: return@composable
                    ImageViewerScreen(
                        activity = activity,
                        fileId = fileId,
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.VIDEO,
                    arguments = listOf(navArgument("fileId") { type = NavType.LongType }),
                ) { entry ->
                    val fileId = entry.arguments?.getLong("fileId") ?: return@composable
                    VideoPlayerScreen(
                        activity = activity,
                        fileId = fileId,
                        onBack = { navController.popBackStack() },
                        onDeleted = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        activity = activity,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.BROWSER) {
                    PrivateBrowserScreen(
                        activity = activity,
                        onBack = { goTab(navController, Routes.HOME) },
                    )
                }
            }
        }
    }
}

/**
 * Tab-style navigation: trims the stack back to the Gallery root, saves the
 * outgoing tab's state and restores the incoming one. Leaving the Browser tab
 * disposes its composition, which wipes all browsing data (PRD §38).
 */
private fun goTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        // HOME is the permanent stack base established right after setup/unlock.
        popUpTo(Routes.HOME) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}
