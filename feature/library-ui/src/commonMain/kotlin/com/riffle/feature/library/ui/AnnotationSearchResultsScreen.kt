package com.riffle.feature.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.HighlightColor
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.AnnotationSearchViewModel
import com.riffle.feature.library.AudiobookBookmarkSearchResult
import com.riffle.feature.reader.ui.formatTemplate
import com.riffle.feature.source.ui.asAuthHeader

/**
 * Every annotation and audiobook bookmark in a library matching a search query. Rendered by both
 * hosts.
 *
 * Unlike the playlist and facet screens this one needs no host slot: the rows fetch their cover
 * through Compose Multiplatform's Coil, so both platforms draw the same card — which is also how
 * iOS gets cover art here at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationSearchResultsScreen(
    viewModel: AnnotationSearchViewModel,
    labels: AnnotationSearchLabels,
    onNavigateBack: () -> Unit,
    onAnnotationSelected: (AnnotationSearchResult) -> Unit,
    onAudiobookBookmarkSelected: (AudiobookBookmarkSearchResult) -> Unit,
) {
    val results by viewModel.results.collectAsState()
    val bookmarkResults by viewModel.bookmarkResults.collectAsState()
    val token by viewModel.authToken.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatTemplate(labels.titleTemplate, viewModel.query)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LibraryUiGlyphs.ArrowBack, contentDescription = labels.back)
                    }
                },
            )
        },
    ) { padding ->
        if (results.isEmpty() && bookmarkResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatTemplate(labels.noResultsTemplate, viewModel.query),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("annotation-search-results"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(results, key = { "anno_${it.annotation.id}" }) { result ->
                AnnotationResultRow(
                    result = result,
                    token = token,
                    labels = labels,
                    onClick = { onAnnotationSelected(result) },
                )
            }
            items(bookmarkResults, key = { "abm_${it.bookmark.id}" }) { result ->
                AudiobookBookmarkResultRow(
                    result = result,
                    token = token,
                    labels = labels,
                    onClick = { onAudiobookBookmarkSelected(result) },
                )
            }
        }
    }
}

@Composable
fun AnnotationResultRow(
    result: AnnotationSearchResult,
    token: String,
    labels: AnnotationSearchLabels,
    onClick: () -> Unit,
) {
    val annotation = result.annotation
    // The stored type token, never the literal: `annotation.type == "bookmark"` against the
    // database's "BOOKMARK" is exactly the bug AGENTS.md's constants rule exists for.
    val isBookmark = annotation.type == AnnotationEntity.TYPE_BOOKMARK
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("annotation-result-${annotation.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            // Leading: highlight colour bar, or a bookmark glyph.
            Box(modifier = Modifier.size(width = 16.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                if (isBookmark) {
                    Icon(
                        LibraryUiGlyphs.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    val color = HighlightColor.fromToken(annotation.color)
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = Color(color.argb.toLong() and 0xFFFFFFFFL),
                        modifier = Modifier.size(width = 4.dp, height = 40.dp),
                    ) {}
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val primary = if (isBookmark) {
                    annotation.bookmarkTitle.ifBlank { labels.bookmarkFallbackTitle }
                } else {
                    annotation.textSnippet
                }
                Text(
                    text = primary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = annotation.note
                if (!isBookmark && !note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                ResultBookLine(coverUrl = result.bookCoverUrl, title = result.bookTitle, token = token)
            }
        }
    }
}

@Composable
fun AudiobookBookmarkResultRow(
    result: AudiobookBookmarkSearchResult,
    token: String,
    labels: AnnotationSearchLabels,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audiobook-bookmark-result-${result.bookmark.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(width = 16.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    LibraryUiGlyphs.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.bookmark.title.ifBlank { labels.audiobookBookmarkFallbackTitle },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                ResultBookLine(coverUrl = result.bookCoverUrl, title = result.bookTitle, token = token)
            }
        }
    }
}

/** The "which book is this from" line under a result: a small authenticated cover plus the title. */
@Composable
private fun ResultBookLine(coverUrl: String?, title: String, token: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(coverUrl)
                .httpHeaders(NetworkHeaders.Builder().add("Authorization", token.asAuthHeader()).build())
                .crossfade(true)
                .build(),
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The strings [AnnotationSearchResultsScreen] draws, supplied by the host. Same arrangement and
 * same reason as [PlaylistLabels].
 */
data class AnnotationSearchLabels(
    /** Back-arrow content description. */
    val back: String,
    /** `"Annotations · '%1$s'"` */
    val titleTemplate: String,
    /** `"No annotations for \"%1$s\""` */
    val noResultsTemplate: String,
    /** Shown for a bookmark the user never named. */
    val bookmarkFallbackTitle: String,
    /** Shown for an audiobook bookmark the user never named. */
    val audiobookBookmarkFallbackTitle: String,
) {
    companion object {
        /**
         * Verbatim from `app/src/main/res/values/strings.xml` (and, where Android still inlines a
         * literal, from the literal). Used by the iOS host.
         */
        val English = AnnotationSearchLabels(
            back = "Back",
            titleTemplate = "Annotations · '%1\$s'",
            noResultsTemplate = "No annotations for \"%1\$s\"",
            bookmarkFallbackTitle = "Bookmark",
            audiobookBookmarkFallbackTitle = "Audiobook Bookmark",
        )
    }
}
