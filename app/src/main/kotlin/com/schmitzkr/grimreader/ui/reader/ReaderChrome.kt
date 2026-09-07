package com.schmitzkr.grimreader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The readers' floating chrome: a rounded bar in the theme's surface, inset
 * from the edges like the app's navigation bar and mini player, instead of
 * an edge-to-edge black strip. Text and icons take the surface's colours,
 * so it sits as well on a white PDF page as on a black comic page.
 */
@Composable
fun ReaderBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
