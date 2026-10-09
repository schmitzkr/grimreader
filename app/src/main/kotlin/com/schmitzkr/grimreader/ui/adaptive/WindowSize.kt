package com.schmitzkr.grimreader.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The window's width class, at the usual breakpoints: phones, small tablets and foldables, large tablets. */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberWindowWidth(): WindowWidth {
    val px = LocalWindowInfo.current.containerSize.width
    val width = with(LocalDensity.current) { px.toDp() }
    return when {
        width < 600.dp -> WindowWidth.COMPACT
        width < 840.dp -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}

/** The window's height class: COMPACT is a phone held in landscape, where stacked bottom chrome eats the screen. */
enum class WindowHeight { COMPACT, REGULAR }

@Composable
fun rememberWindowHeight(): WindowHeight {
    val px = LocalWindowInfo.current.containerSize.height
    val height = with(LocalDensity.current) { px.toDp() }
    return if (height < 480.dp) WindowHeight.COMPACT else WindowHeight.REGULAR
}

/** Keeps a page of settings or text at a readable width on a tablet, centred, full width on a phone. */
@Composable
fun ReadableWidth(maxWidth: Dp = 720.dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
    }
}
