package com.schmitzkr.grimreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.BuildConfig
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.ui.formatBytes
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.data.ThemeMode
import com.schmitzkr.grimreader.data.UpdateRepository
import com.schmitzkr.grimreader.data.UpdateState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import com.schmitzkr.grimreader.ui.components.GrimCard
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.theme.Accent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settings: Settings,
    private val updates: UpdateRepository,
    downloads: DownloadManager,
) : ViewModel() {
    val downloadStates = downloads.state
    val user = auth.currentUser
    val latestRelease = updates.latest
    val updateState = updates.state

    fun checkForUpdates() = viewModelScope.launch { updates.check() }

    /** The What's New sheet needs the latest release even when no check was due. */
    fun ensureLatestKnown() = viewModelScope.launch { if (updates.latest.value == null) updates.check() }
    val serverUrl = settings.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val themeMode = settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val accent = settings.accent.stateIn(viewModelScope, SharingStarted.Eagerly, "violet")
    val oledBlack = settings.oledBlack.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoRewind = settings.autoRewind.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val shakeToReset = settings.shakeToReset.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAccent(accent: Accent) = viewModelScope.launch { settings.setAccent(accent.name.lowercase()) }
    fun setOledBlack(on: Boolean) = viewModelScope.launch { settings.setOledBlack(on) }
    fun setAutoRewind(on: Boolean) = viewModelScope.launch { settings.setAutoRewind(on) }
    fun setShakeToReset(on: Boolean) = viewModelScope.launch { settings.setShakeToReset(on) }
    fun signOut() = viewModelScope.launch { auth.signOut() }
    fun changeServer() = viewModelScope.launch { auth.changeServer() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenStats: () -> Unit, onOpenDownloads: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val oled by vm.oledBlack.collectAsStateWithLifecycle()
    val autoRewind by vm.autoRewind.collectAsStateWithLifecycle()
    val shakeToReset by vm.shakeToReset.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    var whatsNew by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.ensureLatestKnown() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 160.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp)) }

        item { SectionLabel("Account", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Column {
                    Item(
                        title = user?.displayName ?: "Signed in",
                        subtitle = listOfNotNull(
                            user?.username?.takeIf { it != user?.displayName },
                            user?.email,
                            if (user?.signedInWithSso == true) "Signed in with SSO" else null,
                        ).joinToString(" · ").ifEmpty { "Loading account…" },
                    )
                    HorizontalDivider()
                    Item(title = "Server", subtitle = serverUrl ?: "Not set")
                    HorizontalDivider()
                    Item("Your stats", "Streaks, this week's listening and reading, books in progress", onOpenStats)
                }
            }
        }

        item { SectionLabel("Appearance", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                icon = {},
                            ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    Text("Accent", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accent.entries.forEach { option ->
                            val selected = option.name.equals(accent, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(option.color, CircleShape)
                                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { vm.setAccent(option) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Rounded.Check, option.label, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    ToggleRow("Pure black in dark mode", "Deeper blacks on OLED screens", oled, vm::setOledBlack)
                }
            }
        }

        item { SectionLabel("Storage", Modifier.padding(0.dp)) }
        item {
            val downloads by vm.downloadStates.collectAsStateWithLifecycle()
            val done = downloads.values.filter { it.isDone }
            GrimCard(Modifier.fillMaxWidth()) {
                Item(
                    "Downloads",
                    if (done.isEmpty()) "Nothing on this device yet"
                    else "${done.size} book${if (done.size == 1) "" else "s"} · ${formatBytes(done.sumOf { it.bytes })}",
                    onOpenDownloads,
                )
            }
        }

        item { SectionLabel("Listening", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ToggleRow(
                        "Auto-rewind after a pause",
                        "Back up a few seconds on resume, more after a long pause",
                        autoRewind, vm::setAutoRewind,
                    )
                    HorizontalDivider()
                    ToggleRow(
                        "Shake to reset the sleep timer",
                        "A firm shake restarts a running timer at the same length",
                        shakeToReset, vm::setShakeToReset,
                    )
                }
            }
        }

        item { SectionLabel("About", Modifier.padding(0.dp)) }
        item {
            val latest by vm.latestRelease.collectAsStateWithLifecycle()
            val updateState by vm.updateState.collectAsStateWithLifecycle()
            GrimCard(Modifier.fillMaxWidth()) {
                Column {
                    Item(
                        "What's new",
                        "Installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                            (latest?.let { " · latest ${it.version}" } ?: ""),
                    ) { whatsNew = true }
                    HorizontalDivider()
                    Item(
                        "Check for updates",
                        when (val u = updateState) {
                            is UpdateState.Checking -> "Checking…"
                            is UpdateState.Available -> "Version ${u.release.version} is available on Home"
                            else -> "Looks for a newer release on GitHub"
                        },
                    ) { vm.checkForUpdates() }
                    HorizontalDivider()
                    Item("GrimReader", "MIT licence · github.com/schmitzkr/grimreader")
                    HorizontalDivider()
                    Item("Sign out", "Keep the server, return to sign-in") { confirm = "signout" }
                    HorizontalDivider()
                    Item("Change server", "Sign out and connect to a different server") { confirm = "server" }
                }
            }
        }
    }

    if (whatsNew) {
        val latest by vm.latestRelease.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { whatsNew = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(latest?.let { "Version ${it.version}" } ?: "What's new", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    latest?.notes?.takeIf { it.isNotBlank() }
                        ?: if (latest == null) "Release notes could not be loaded. Check your connection and try again."
                        else "No notes for this release.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (which == "signout") "Sign out?" else "Change server?") },
            text = {
                Text(
                    if (which == "signout") "You will need to sign in again to read or listen."
                    else "This signs you out and asks for a server address again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (which == "signout") vm.signOut() else vm.changeServer()
                    confirm = null
                }) { Text(if (which == "signout") "Sign out" else "Change server") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Item(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
