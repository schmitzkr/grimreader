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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.LaunchedEffect
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
import com.schmitzkr.grimreader.ui.stats.StatsScreen
import com.schmitzkr.grimreader.ui.downloads.DownloadsScreen
import com.schmitzkr.grimreader.ui.browse.AuthorTitledList
import com.schmitzkr.grimreader.ui.browse.BookListViewModel
import com.schmitzkr.grimreader.ui.browse.BrowseScreen
import com.schmitzkr.grimreader.ui.browse.TitledBookList
import com.schmitzkr.grimreader.ui.search.SearchScreen
import com.schmitzkr.grimreader.ui.reader.EpubReaderScreen
import com.schmitzkr.grimreader.ui.reader.PageReaderScreen
import com.schmitzkr.grimreader.core.model.PageFormat
import android.net.Uri
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.rounded.Explore
import androidx.hilt.navigation.compose.hiltViewModel

object Routes {
    const val HOME = "home"
    const val LIBRARIES = "libraries"
    const val BROWSE = "browse"
    const val SETTINGS = "settings"
    const val LIBRARY = "library/{id}"
    const val BOOK = "book/{id}"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SERIES = "series/{name}"
    const val AUTHOR = "author/{id}"
    const val SHELF = "shelf/{id}"
    const val MAGIC_SHELF = "magic/{id}"

    fun library(id: Long) = "library/$id"
    fun book(id: Long) = "book/$id"
    fun series(name: String) = "series/${Uri.encode(name)}"
    fun author(id: Long) = "author/$id"
    fun shelf(id: Long) = "shelf/$id"
    fun magicShelf(id: Long) = "magic/$id"

    const val STATS = "stats"
    const val DOWNLOADS = "downloads"
    const val EPUB = "epub/{id}?file={file}"
    fun epub(id: Long, fileId: Long? = null) = "epub/$id" + (fileId?.let { "?file=$it" } ?: "")
    const val READER = "reader/{format}/{id}"
    fun reader(format: PageFormat, id: Long) = "reader/${format.name.lowercase()}/$id"
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
    NavItem(Routes.BROWSE, "Browse", Icons.Outlined.Explore, Icons.Rounded.Explore),
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
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    onOpenSearch = { nav.navigate(Routes.SEARCH) },
                    onOpenMagicShelf = { nav.navigate(Routes.magicShelf(it)) },
                )
            }
            composable(Routes.BROWSE) {
                BrowseScreen(
                    onOpenSeries = { nav.navigate(Routes.series(it)) },
                    onOpenAuthor = { nav.navigate(Routes.author(it)) },
                    onOpenShelf = { nav.navigate(Routes.shelf(it)) },
                    onOpenMagicShelf = { nav.navigate(Routes.magicShelf(it)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(onBack = { nav.popBackStack() }, onOpenBook = { nav.navigate(Routes.book(it)) })
            }
            composable(Routes.SERIES, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                val name = Uri.decode(entry.arguments!!.getString("name")!!)
                TitledBookList(
                    title = name,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    vm = hiltViewModel(key = "series-$name"),
                    start = { it.seriesBooks(name) },
                )
            }
            composable(Routes.AUTHOR, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                val vm: BookListViewModel = hiltViewModel(key = "author-$id")
                AuthorTitledList(id = id, vm = vm, onBack = { nav.popBackStack() }, onOpenBook = { nav.navigate(Routes.book(it)) })
            }
            composable(Routes.SHELF, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                TitledBookList(
                    title = "Shelf",
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    vm = hiltViewModel(key = "shelf-$id"),
                    start = { it.shelfBooks(id) },
                )
            }
            composable(Routes.MAGIC_SHELF, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments!!.getLong("id")
                TitledBookList(
                    title = "Magic shelf",
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                    vm = hiltViewModel(key = "magic-$id"),
                    start = { it.magicShelfBooks(id) },
                )
            }
            composable(Routes.LIBRARIES) {
                LibrariesScreen(
                    onOpenLibrary = { nav.navigate(Routes.library(it)) },
                    onOpenBook = { nav.navigate(Routes.book(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenStats = { nav.navigate(Routes.STATS) }, onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) })
            }
            composable(Routes.DOWNLOADS) { DownloadsScreen(onBack = { nav.popBackStack() }, onOpenBook = { nav.navigate(Routes.book(it)) }) }
            composable(Routes.STATS) { StatsScreen(onBack = { nav.popBackStack() }) }
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
                    onOpenReader = { format -> nav.navigate(Routes.reader(format, entry.arguments!!.getLong("id"))) },
                    onOpenEpub = { fileId -> nav.navigate(Routes.epub(entry.arguments!!.getLong("id"), fileId)) },
                )
            }
            composable(Routes.PLAYER) { PlayerScreen(onBack = { nav.popBackStack() }) }
            composable(
                Routes.EPUB,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("file") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { entry ->
                EpubReaderScreen(
                    bookId = entry.arguments!!.getLong("id"),
                    fileId = entry.arguments!!.getLong("file").takeIf { it >= 0 },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.READER,
                arguments = listOf(
                    navArgument("format") { type = NavType.StringType },
                    navArgument("id") { type = NavType.LongType },
                ),
            ) { entry ->
                val format = PageFormat.valueOf(entry.arguments!!.getString("format")!!.uppercase())
                PageReaderScreen(
                    bookId = entry.arguments!!.getLong("id"),
                    format = format,
                    onBack = { nav.popBackStack() },
                )
            }
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
                // Swiping the pill away, either direction, stops playback (the
                // position is saved first) and clears it from the screen.
                val dismiss = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value != SwipeToDismissBoxValue.Settled) vm.player.stop()
                        value != SwipeToDismissBoxValue.Settled
                    },
                )
                LaunchedEffect(playback.bookId) { dismiss.snapTo(SwipeToDismissBoxValue.Settled) }
                SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = {},
                    modifier = Modifier.widthIn(max = 560.dp),
                ) {
                    MiniPlayer(
                        state = playback,
                        onTap = { nav.navigate(Routes.PLAYER) },
                        onTogglePlay = vm.player::togglePlayPause,
                        onForward = vm.player::fastForward,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
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
