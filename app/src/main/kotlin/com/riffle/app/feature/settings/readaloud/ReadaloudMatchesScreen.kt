package com.riffle.app.feature.settings.readaloud

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
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.UnmatchedReadaloud

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            sectionHeader("Pending Review (${review.pending.size})")
            if (review.pending.isEmpty()) {
                emptyRow("Nothing waiting for review.")
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

            sectionHeader("Unmatched (${review.unmatched.size})")
            if (review.unmatched.isEmpty()) {
                emptyRow("Every readaloud is matched or under review.")
            } else {
                items(review.unmatched, key = { it.storytellerBookId }) { book ->
                    UnmatchedReadaloudRow(
                        book = book,
                        tokens = tokens,
                        onMatchManually = { pairingBookId = book.storytellerBookId },
                    )
                    HorizontalDivider()
                }
            }

            sectionHeader("Confirmed (${review.confirmed.size})")
            if (review.confirmed.isEmpty()) {
                emptyRow("No confirmed links yet.")
            } else {
                items(review.confirmed, key = { it.storytellerBookId }) { link ->
                    ConfirmedReadaloudRow(
                        link = link,
                        onUnlink = { viewModel.unlinkBook(link) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pairingBookId?.let { bookId ->
        val query by viewModel.pickerQuery.collectAsState()
        val results by viewModel.pickerResults.collectAsState()
        // Reactively reflects links as they're added/removed so the picker can stay open and let
        // the user attach several ABS items (e.g. ebook + audiobook) to one readaloud.
        val linkedItemIds = review.confirmed
            .firstOrNull { it.storytellerBookId == bookId }
            ?.targets?.map { it.absLibraryItemId }?.toSet()
            ?: emptySet()
        AbsPickerDialog(
            query = query,
            results = results,
            tokens = tokens,
            linkedItemIds = linkedItemIds,
            onQueryChange = viewModel::onPickerQueryChange,
            onLink = { item -> viewModel.pairManually(bookId, item) },
            onUnlink = { item -> viewModel.unlinkAbsItem(item) },
            onDismiss = { pairingBookId = null },
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
                token = tokens[candidate.absServerId],
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(link.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            link.targets.forEach { target ->
                Text(
                    "Linked to: ${target.absTitle} · ${target.absLibraryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onUnlink) { Text("Unlink") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AbsPickerDialog(
    query: String,
    results: List<AbsPickerItem>,
    tokens: Map<String, String>,
    linkedItemIds: Set<String>,
    onQueryChange: (String) -> Unit,
    onLink: (AbsPickerItem) -> Unit,
    onUnlink: (AbsPickerItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Match manually", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A readaloud can link to more than one book (e.g. an ebook and an audiobook).",
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
                    items(results, key = { it.absServerId + "/" + it.absLibraryItemId }) { item ->
                        val isLinked = item.absLibraryItemId in linkedItemIds
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(coverUrl = item.coverUrl, token = tokens[item.absServerId])
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
            .apply { if (token != null) addHeader("Authorization", "Bearer $token") }
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
