package com.schmitzkr.grimreader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.schmitzkr.grimreader.data.AppState
import com.schmitzkr.grimreader.ui.auth.LoginScreen
import com.schmitzkr.grimreader.ui.book.BookDetailScreen
import com.schmitzkr.grimreader.ui.components.FloatingNavBar
import com.schmitzkr.grimreader.ui.components.NavItem
import com.schmitzkr.grimreader.ui.home.HomeScreen
import com.schmitzkr.grimreader.ui.library.LibrariesScreen
import com.schmitzkr.grimreader.ui.library.LibraryScreen
import com.schmitzkr.grimreader.ui.onboarding.ServerUrlScreen
import com.schmitzkr.grimreader.ui.player.MiniPlayer
import com.schmitzkr.grimreader.ui.player.PlayerScreen
import com.schmitzkr.grimreader.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val LIBRARIES = "libraries"
    const val SETTINGS = "settings"
    const val LIBRARY = "library/{id}"
    const val BOOK = "book/{id}"
    const val PLAYER = "player"

    fun library(id: Long) = "library/$id"
    fun book(id: Long) = "book/$id"
}

/** Picks the screen from the auth state; everything signed-in lives in [MainShell]. */
@Composable
fun AppRoot(vm: RootViewModel) {
    val state by vm.appState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (val s = state) {
            AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AppState.NeedsServer -> ServerUrlScreen()
            is AppState.SignedOut -> LoginScreen(sessionExpired = s.sessionExpired)
            AppState.SignedIn -> MainShell(vm)
        }
    }
}

private val tabs = listOf(
    NavItem(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    NavItem(Routes.LIBRARIES, "Libraries", Icons.Outlined.LibraryBooks, Icons.Rounded.LibraryBooks),
    NavItem(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@Composable
private fun MainShell(vm: RootViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onTab = tabs.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val onPlayer = destination?.route == Routes.PLAYER

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) { HomeScreen(onOpenBook = { nav.navigate(Routes.book(it)) }) }
            composable(Routes.LIBRARIES) {
                LibrariesScreen(
                    onOpenLibrary = { nav.navigate(Routes.library(it)) },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                Routes.LIBRARY,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                LibraryScreen(
                    libraryId = entry.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                )
            }
            composable(
                Routes.BOOK,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                BookDetailScreen(
                    bookId = entry.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) },
                )
            }
            composable(Routes.PLAYER) { PlayerScreen(onBack = { nav.popBackStack() }) }
        }

        // The floating bottom: mini player above the tab bar, both inset
        // from the edges so nothing touches the screen edge.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = playback.hasBook && !onPlayer,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(
                    state = playback,
                    onTap = { nav.navigate(Routes.PLAYER) },
                    onTogglePlay = vm.player::togglePlayPause,
                    onForward = vm.player::fastForward,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            AnimatedVisibility(visible = onTab, enter = fadeIn(), exit = fadeOut()) {
                FloatingNavBar(
                    items = tabs,
                    selectedRoute = tabs.firstOrNull { tab ->
                        destination?.hierarchy?.any { it.route == tab.route } == true
                    }?.route,
                    onSelect = { route -> nav.navigateToTab(route) },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
