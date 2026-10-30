package com.schmitzkr.grimreader.ui.reader

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Holds the screen awake while a reader is composed, so a slow page doesn't
 * let the display dim mid-sentence. A plain window flag: no permission, no
 * sensors, and it is dropped the moment the reader leaves the screen.
 */
@Composable
fun KeepScreenOn(setting: Flow<Boolean>) {
    val enabled by setting.collectAsStateWithLifecycle(true)
    val window = (LocalView.current.context as? Activity)?.window
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
