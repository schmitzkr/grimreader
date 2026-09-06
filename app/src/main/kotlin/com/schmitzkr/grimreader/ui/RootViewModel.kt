package com.schmitzkr.grimreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.data.AppState
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.data.ThemeMode
import com.schmitzkr.grimreader.playback.PlayerController
import com.schmitzkr.grimreader.ui.theme.Accent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.VIOLET,
    val oledBlack: Boolean = false,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val auth: AuthRepository,
    settings: Settings,
    val player: PlayerController,
) : ViewModel() {
    val appState: StateFlow<AppState> = auth.state

    val theme: StateFlow<ThemeSettings> = combine(settings.themeMode, settings.accent, settings.oledBlack) { m, a, o ->
        ThemeSettings(m, Accent.byName(a), o)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())
}
