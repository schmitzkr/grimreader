package com.schmitzkr.grimreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.schmitzkr.grimreader.core.model.Book

/** Small uppercase, letter-spaced heading above each section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let { Spacer(Modifier.width(6.dp)); it() }
    }
}

/** A cover with rounded corners, a progress hairline while started and a tick when finished. */
@Composable
fun BookCover(
    book: Book,
    coverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
    showProgress: Boolean = true,
    fallbackUrl: String? = null,
    downloaded: Boolean = false,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    // An audiobook's own art may not exist on the server; fall back to the
    // book cover once the first request fails.
    var useFallback by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier
            .aspectRatio(book.coverAspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        AsyncImage(
            model = if (useFallback && fallbackUrl != null) fallbackUrl else coverUrl,
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            onError = { if (fallbackUrl != null && !useFallback) useFallback = true },
            modifier = Modifier.fillMaxSize(),
        )
        if (downloaded) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        if (!showProgress) return@Box
        val progress = book.normalizedReadProgress
        when {
            book.isFinished -> Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            progress != null && progress > 0 && progress < 1 -> LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
                trackColor = Color.Black.copy(alpha = 0.35f),
                drawStopIndicator = {},
            )
        }
    }
}

/** A cover with title and author underneath, the tile every grid and row uses. */
@Composable
fun BookTile(
    book: Book,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    downloaded: Boolean = false,
) {
    Column(modifier.clickable(onClick = onClick)) {
        BookCover(book, coverUrl, Modifier.fillMaxWidth(), fallbackUrl = fallbackUrl, downloaded = downloaded)
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val author = book.authors.firstOrNull()
        if (!author.isNullOrBlank()) {
            Text(
                author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BookGrid(
    books: List<Book>,
    coverUrl: (Book) -> String,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    fallbackUrl: ((Book) -> String?)? = null,
    downloadedIds: Set<Long> = emptySet(),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(books, key = { it.id }) { book ->
            BookTile(book, coverUrl(book), onClick = { onOpen(book.id) }, fallbackUrl = fallbackUrl?.invoke(book), downloaded = book.id in downloadedIds)
        }
    }
}

/** Filled with the accent when selected, a hairline outline otherwise. */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (selected) scheme.onPrimary else scheme.onSurface
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) scheme.primary else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            count?.let {
                Text(
                    "$it",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon?.let {
            Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A rounded card on the next surface step with a hairline border. */
@Composable
fun GrimCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) { content() }
}
