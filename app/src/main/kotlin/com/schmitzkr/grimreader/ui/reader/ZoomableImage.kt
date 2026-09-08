package com.schmitzkr.grimreader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to toggle 2.5× and
 * back, single tap reported to the caller as the horizontal position it
 * landed at (0f left edge, 1f right edge) so a reader can turn pages from
 * the edges and reserve the middle for its own chrome toggle. Pans are
 * clamped so the page never leaves the viewport.
 */
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    onTap: (xFraction: Float) -> Unit,
    onZoomChanged: (Boolean) -> Unit = {},
    resetKey: Any? = null,
    content: @Composable () -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    LaunchedEffect(scale.isZoomed) { onZoomChanged(scale.isZoomed) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1)) / 2
        val maxY = (size.height * (s - 1)) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tap -> onTap(tap.x / size.width.toFloat().coerceAtLeast(1f)) },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((centre - tap) * (scale - 1), scale)
                        }
                    },
                )
            }
            .pointerInput(resetKey) {
                // Only a pinch or a zoomed-in drag is ours; an unzoomed
                // single-finger swipe is left unconsumed for the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size > 1 || scale.isZoomed) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (newScale == 1f) Offset.Zero else clamp(offset + pan, newScale)
                            scale = newScale
                            if (zoom != 1f || pan != Offset.Zero) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** True while zoomed in, so a pager can stop swiping between pages. */
val Float.isZoomed: Boolean get() = this > 1.01f
