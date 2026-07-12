package com.riffle.app.feature.settings.readaloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.riffle.core.domain.AbsCandidate
import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.UnmatchedReadaloud
import com.riffle.app.ui.source.asAuthHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadaloudMatchesScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReadaloudMatchesViewModel = hiltViewModel(),
) {
    val review by viewModel.review.collectAsState()
    val tokens by viewModel.tokensByServer.collectAsState()

    // When opened from a readaloud's "Pair manually" footer, hold the book id whose picker is open.
    var pairingBookId by remember { mutableStateOf(viewModel.pairBookId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readaloud matches") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // A confirmed readaloud is "Matched" only when both an ebook and an audiobook are linked;
        // otherwise it's "Partially matched" (one side still missing).
        val (partiallyMatched, matched) = remember(review.confirmed) {
            review.confirmed.partition { it.isIncomplete }
        }
        // Opens the manual picker for a readaloud, scoped to the slot that was tapped.
        val openPicker = { bookId: String, filter: AbsFormatFilter ->
            viewModel.setPickerFilter(filter)
            pairingBookId = bookId
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            sectionHeader("Unmatched (${review.unmatched.size})")
            if (review.unmatched.isEmpty()) {
                emptyRow("Every readaloud is matched or suggested.")
            } else {
                items(review.unmatched, key = { it.storytellerBookId }) { book ->
                    UnmatchedReadaloudRow(
                        book = book,
                        tokens = tokens,
                        onMatchManually = { openPicker(book.storytellerBookId, AbsFormatFilter.ANY) },
                    )
                    HorizontalDivider()
                }
            }

            sectionHeader("Suggested (${review.pending.size})")
            if (review.pending.isEmpty()) {
                emptyRow("No suggested matches right now.")
            } else {
                items(review.pending, key = { it.storytellerBookId }) { book ->
                    PendingReadaloudCard(
                        book = book,
                        tokens = tokens,
                        onConfirm = { candidate -> viewModel.confirm(book, candidate) },
                        onDismissCandidate = { candidate -> viewModel.dismissCandidate(book, candidate) },
                        onNoMatch = { viewModel.dismissBook(book) },
                    )
                    HorizontalDivider()
                }
            }

            confirmedSection(
                title = "Partially matched",
                links = partiallyMatched,
                emptyText = "Every match has both an ebook and an audiobook.",
                onUnlink = { viewModel.unlinkBook(it) },
                onAddMissing = { link, filter -> openPicker(link.storytellerBookId, filter) },
            )

            confirmedSection(
                title = "Matched",
                links = matched,
                emptyText = "No fully matched readalouds yet.",
                onUnlink = { viewModel.unlinkBook(it) },
                onAddMissing = { link, filter -> openPicker(link.storytellerBookId, filter) },
            )
        }
    }

    pairingBookId?.let { bookId ->
        val query by viewModel.pickerQuery.collectAsState()
        val results by viewModel.pickerResults.collectAsState()
        val filter by viewModel.pickerFilter.collectAsState()
        // Reactively reflects links as they're added/removed so the picker can stay open and let
        // the user attach several ABS items (e.g. ebook + audiobook) to one readaloud.
        val linkedItemIds = review.confirmed
            .firstOrNull { it.storytellerBookId == bookId }
            ?.targets?.map { it.absLibraryItemId }?.toSet()
            ?: emptySet()
        AbsPickerDialog(
            query = query,
            results = results,
            filter = filter,
            tokens = tokens,
            linkedItemIds = linkedItemIds,
            onQueryChange = viewModel::onPickerQueryChange,
            onLink = { item -> viewModel.pairManually(bookId, item) },
            onUnlink = { item -> viewModel.unlinkAbsItem(item) },
            onDismiss = {
                pairingBookId = null
                viewModel.closePicker()
            },
        )
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
    }
}

private fun LazyListScope.emptyRow(text: String) {
    item(key = "empty:$text") {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** A "Partially matched" / "Matched" section: a header plus one two-slot row per confirmed match. */
private fun LazyListScope.confirmedSection(
    title: String,
    links: List<ConfirmedReadaloud>,
    emptyText: String,
    onUnlink: (ConfirmedReadaloud) -> Unit,
    onAddMissing: (ConfirmedReadaloud, AbsFormatFilter) -> Unit,
) {
    sectionHeader("$title (${links.size})")
    if (links.isEmpty()) {
        emptyRow(emptyText)
    } else {
        items(links, key = { it.storytellerBookId }) { link ->
            ConfirmedReadaloudRow(
                link = link,
                onUnlink = { onUnlink(link) },
                onAddMissing = { filter -> onAddMissing(link, filter) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PendingReadaloudCard(
    book: PendingReadaloud,
    tokens: Map<String, String>,
    onConfirm: (AbsCandidate) -> Unit,
    onDismissCandidate: (AbsCandidate) -> Unit,
    onNoMatch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(coverUrl = book.coverUrl, token = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        book.candidates.forEach { candidate ->
            CandidateRow(
                candidate = candidate,
                token = tokens[candidate.absSourceId],
                onConfirm = { onConfirm(candidate) },
                onDismiss = { onDismissCandidate(candidate) },
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onNoMatch) {
            Text("No match — don't ask again")
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: AbsCandidate,
    token: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverUrl = candidate.coverUrl, token = token)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.absTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${candidate.absAuthor} · ${candidate.absLibraryName} · ${(candidate.score * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Button(onClick = onConfirm) { Text("Confirm") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun UnmatchedReadaloudRow(
    book: UnmatchedReadaloud,
    tokens: Map<String, String>,
    onMatchManually: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(coverUrl = book.coverUrl, token = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onMatchManually) { Text("Match manually…") }
    }
}

@Composable
private fun ConfirmedReadaloudRow(
    link: ConfirmedReadaloud,
    onUnlink: () -> Unit,
    onAddMissing: (AbsFormatFilter) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                link.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnlink) { Text("Unmatch") }
        }
        Spacer(Modifier.height(8.dp))
        // Two fixed slots — a combined ABS item fills both; a one-sided match leaves the other
        // slot empty and tappable so the missing side can be linked in place.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormatSlot(
                label = "📖 Ebook",
                targets = link.targets.filter { it.hasEbook },
                onAdd = { onAddMissing(AbsFormatFilter.EBOOK) },
                kind = FormatSlotKind.EBOOK,
                modifier = Modifier.weight(1f),
            )
            FormatSlot(
                label = "🎧 Audiobook",
                targets = link.targets.filter { it.hasAudio },
                onAdd = { onAddMissing(AbsFormatFilter.AUDIO) },
                kind = FormatSlotKind.AUDIOBOOK,
                modifier = Modifier.weight(1f),
            )
        }
        // Streaming status (ADR 0028): how this book's audio is delivered.
        when (link.streamingStatus) {
            ConfirmedReadaloud.StreamingStatus.STREAMING ->
                Text("Streaming", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            ConfirmedReadaloud.StreamingStatus.DOWNLOAD_ONLY_NO_AUDIOBOOK ->
                Text("Download only · no audiobook linked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConfirmedReadaloud.StreamingStatus.DOWNLOAD_ONLY_MISMATCH ->
                Text("Download only · audio doesn't match", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            ConfirmedReadaloud.StreamingStatus.UNKNOWN -> Unit
        }
    }
}

private enum class FormatSlotKind { EBOOK, AUDIOBOOK }

@Composable
private fun FormatSlot(
    label: String,
    targets: List<ConfirmedReadaloud.ConfirmedTarget>,
    onAdd: () -> Unit,
    kind: FormatSlotKind,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val isEmpty = targets.isEmpty()
    // A missing slot is flagged with a warning amber (Material3 has no warning role). Tinted fill +
    // amber dashed border + amber label so the gap reads as "needs attention", not a normal item.
    val amber = if (isSystemInDarkTheme()) Color(0xFFE6B450) else Color(0xFF9A6700)
    val amberFill = Color(0xFFFFC107).copy(alpha = if (isSystemInDarkTheme()) 0.10f else 0.16f)
    // Filled slots use distinct M3 container tints so ebook and audiobook chips read as different
    // things at a glance, not two identical outlined boxes.
    val filledBg = when (kind) {
        FormatSlotKind.EBOOK -> MaterialTheme.colorScheme.primaryContainer
        FormatSlotKind.AUDIOBOOK -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val filledOn = when (kind) {
        FormatSlotKind.EBOOK -> MaterialTheme.colorScheme.onPrimaryContainer
        FormatSlotKind.AUDIOBOOK -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Column(
        modifier = modifier
            .clip(shape)
            .then(
                if (isEmpty) {
                    Modifier
                        .background(amberFill, shape)
                        .dashedBorder(amber)
                        .clickable(onClick = onAdd)
                } else {
                    Modifier.background(filledBg, shape)
                }
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isEmpty) amber else filledOn,
        )
        Spacer(Modifier.height(3.dp))
        if (isEmpty) {
            Text(
                "＋ Not linked",
                style = MaterialTheme.typography.bodyMedium,
                color = amber,
            )
        } else {
            targets.forEach { target ->
                Text(
                    "${target.absTitle} · ${target.absLibraryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = filledOn,
                )
            }
        }
    }
}

/** A rounded dashed outline, used to render an empty (missing) format slot as a fill-me affordance. */
private fun Modifier.dashedBorder(color: Color): Modifier =
    drawBehind {
        val stroke = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
        )
        drawRoundRect(
            color = color,
            style = stroke,
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbsPickerDialog(
    query: String,
    results: List<AbsPickerItem>,
    filter: AbsFormatFilter,
    tokens: Map<String, String>,
    linkedItemIds: Set<String>,
    onQueryChange: (String) -> Unit,
    onLink: (AbsPickerItem) -> Unit,
    onUnlink: (AbsPickerItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // When opened from an empty slot the list is filtered to that format — say so, otherwise an
    // empty result reads as "nothing found" rather than "filtered to ebooks/audiobooks".
    val title = when (filter) {
        AbsFormatFilter.EBOOK -> "Link an ebook"
        AbsFormatFilter.AUDIO -> "Link an audiobook"
        AbsFormatFilter.ANY -> "Match manually"
    }
    val subtitle = when (filter) {
        AbsFormatFilter.EBOOK -> "Showing ABS items that have an ebook."
        AbsFormatFilter.AUDIO -> "Showing ABS items that have an audiobook."
        AbsFormatFilter.ANY -> "A readaloud can link to more than one book (e.g. an ebook and an audiobook)."
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search ABS by title or author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(results, key = { it.absSourceId + "/" + it.absLibraryItemId }) { item ->
                        val isLinked = item.absLibraryItemId in linkedItemIds
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(coverUrl = item.coverUrl, token = tokens[item.absSourceId])
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.author} · ${item.libraryName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            if (isLinked) {
                                OutlinedButton(onClick = { onUnlink(item) }) { Text("Unlink") }
                            } else {
                                Button(onClick = { onLink(item) }) { Text("Link") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Cover(coverUrl: String?, token: String?) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(coverUrl)
            .apply { if (token != null) addHeader("Authorization", token.asAuthHeader()) }
            .crossfade(true)
            .build(),
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 40.dp, height = 60.dp)
            .clip(RoundedCornerShape(4.dp)),
    )
}
