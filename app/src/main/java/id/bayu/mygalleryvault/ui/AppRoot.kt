package id.bayu.mygalleryvault.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import id.bayu.mygalleryvault.ui.theme.AppMotion
import id.bayu.mygalleryvault.ui.theme.SecureVaultTheme
import id.bayu.mygalleryvault.ui.theme.settleSpring
import kotlin.math.roundToInt

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

    /** Destinations that own a bottom-navigation slot. */
    val tabs = setOf(HOME, FOLDER, BROWSER, SETTINGS)
}

@Composable
fun AppRoot(activity: androidx.fragment.app.FragmentActivity) {
    val app = activity.application as SecureVaultApp
    val navController = rememberNavController()
    val unlocked by VaultSession.isUnlocked.collectAsStateWithLifecycle()
    val vaultCreated = remember { app.container.authRepository.isVaultCreated }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Bottom tabs stay visible on the tab destinations themselves; immersive
    // surfaces (viewer/player/browser) take over the whole screen.
    val showBottomBar = unlocked && currentRoute in Routes.tabs

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
                    VaultBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { route -> goTab(navController, route) },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = if (vaultCreated) Routes.LOCK else Routes.SETUP,
                // padding alone only shifts the content; consumeWindowInsets tells every
                // destination the system insets are already spoken for, so a screen's own top bar
                // stops adding the status-bar height a second time.
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
                // Direction of travel carries the meaning: a tab hop slides sideways the way you
                // moved, a detail push rises from the bottom. Tabs and pushes never mix.
                enterTransition = {
                    val towards = tabSlideDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (towards != null) {
                        slideIntoContainer(towards, settleSpring()) +
                            fadeIn(tween(AppMotion.TAB_MS))
                    } else {
                        fadeIn(tween(AppMotion.DETAIL_MS)) +
                            slideInVertically(tween(AppMotion.DETAIL_MS)) {
                                (it * AppMotion.DETAIL_SLIDE_FRACTION).toInt()
                            }
                    }
                },
                exitTransition = {
                    val towards = tabSlideDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (towards != null) {
                        slideOutOfContainer(towards, settleSpring()) +
                            fadeOut(tween(AppMotion.TAB_MS))
                    } else {
                        fadeOut(tween(AppMotion.TAB_MS))
                    }
                },
                popEnterTransition = {
                    val towards = tabSlideDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (towards != null) {
                        slideIntoContainer(towards, settleSpring()) +
                            fadeIn(tween(AppMotion.TAB_MS))
                    } else {
                        fadeIn(tween(AppMotion.DETAIL_MS))
                    }
                },
                popExitTransition = {
                    val towards = tabSlideDirection(
                        initialState.destination.route,
                        targetState.destination.route,
                    )
                    if (towards != null) {
                        slideOutOfContainer(towards, settleSpring()) +
                            fadeOut(tween(AppMotion.TAB_MS))
                    } else {
                        fadeOut(tween(AppMotion.DETAIL_MS)) +
                            slideOutVertically(tween(AppMotion.DETAIL_MS)) {
                                (it * AppMotion.DETAIL_SLIDE_FRACTION).toInt()
                            }
                    }
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
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(activity = activity)
                }
                composable(Routes.BROWSER) {
                    PrivateBrowserScreen(
                        activity = activity,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Tab-style navigation: trims the stack back to the Gallery root, saves the
 * outgoing tab's state and restores the incoming one. The private browser is no
 * longer a tab, so leaving it (popBackStack) disposes its composition and wipes
 * all browsing data, which is the behaviour PRD §38 asks for.
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

/**
 * Bottom navigation as three flat slots on the canvas. The stock pill indicator is gone and the
 * hairline is a single bar-wide element that slides to the active slot, so changing tabs reads
 * as movement along one row instead of three unrelated buttons lighting up.
 */
@Composable
private fun VaultBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val activeSlot = tabSlot(currentRoute).coerceAtLeast(0)
    val slide by animateFloatAsState(
        targetValue = activeSlot.toFloat(),
        animationSpec = settleSpring(),
        label = "bottomBarSlide",
    )
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val underlineWidth = 22.dp
    val underlineWidthPx = with(LocalDensity.current) { underlineWidth.toPx() }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .onSizeChanged { barWidthPx = it.width.toFloat() },
            ) {
                Row(Modifier.fillMaxSize()) {
                    BottomSlot(
                        label = "Gallery",
                        icon = Icons.Rounded.PhotoLibrary,
                        selected = activeSlot == 0,
                        onClick = { onSelect(Routes.HOME) },
                        modifier = Modifier.weight(1f),
                    )
                    BottomSlot(
                        label = "Browser",
                        icon = Icons.Rounded.Public,
                        selected = activeSlot == 1,
                        onClick = { onSelect(Routes.BROWSER) },
                        modifier = Modifier.weight(1f),
                    )
                    BottomSlot(
                        label = "Settings",
                        icon = Icons.Rounded.Settings,
                        selected = activeSlot == 2,
                        onClick = { onSelect(Routes.SETTINGS) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (barWidthPx > 0f) {
                    val slotWidth = barWidthPx / TAB_SLOTS
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset {
                                IntOffset(
                                    (slotWidth * slide + (slotWidth - underlineWidthPx) / 2f)
                                        .roundToInt(),
                                    0,
                                )
                            }
                            .width(underlineWidth)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** One slot of the bottom bar: glyph over a mono label. The underline belongs to the bar. */
@Composable
private fun BottomSlot(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(bottom = 4.dp)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Number of slots in the bottom bar; the sliding underline divides the bar by this. */
private const val TAB_SLOTS = 3

/** Left-to-right slot of a route in the bottom bar. A folder belongs to the Gallery slot. */
private fun tabSlot(route: String?): Int = when (route) {
    Routes.HOME, Routes.FOLDER -> 0
    Routes.BROWSER -> 1
    Routes.SETTINGS -> 2
    else -> -1
}

/**
 * Slide direction for a hop between two DIFFERENT bottom-bar slots, or null when the change is
 * not a tab hop. A folder shares the Gallery slot, so entering a folder is a detail push, not a
 * sideways hop, and it must not be treated as travel in either direction. Moving to a higher slot
 * slides content leftwards, which is the direction the finger travelled.
 */
private fun tabSlideDirection(fromRoute: String?, toRoute: String?): SlideDirection? {
    val from = tabSlot(fromRoute)
    val to = tabSlot(toRoute)
    if (from < 0 || to < 0 || from == to) return null
    return if (to > from) SlideDirection.Left else SlideDirection.Right
}
