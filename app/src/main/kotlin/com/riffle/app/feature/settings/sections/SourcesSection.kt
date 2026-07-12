package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.server.AddSourceBackend
import com.riffle.app.feature.settings.ExpandableSourceRow
import com.riffle.app.feature.settings.LibraryUiItem
import com.riffle.app.feature.settings.ReadaloudMatchSummary
import com.riffle.app.feature.settings.ReorderableLibraryList
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.feature.settings.idsWithSwap
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptors

/**
 * "Sources" section on the main Settings screen. Renders one row per configured browsable source
 * (Audiobookshelf servers, Local Files, Chitanka) plus an "Add source" button. Each row expands
 * inline to reveal its library visibility+order editor and per-source management controls.
 *
 * Storyteller Services do NOT appear here — they live under the collapsed Readaloud entry, which
 * is a Service (not a Source) per ADR 0020.
 */
@Composable
internal fun SourcesSection(
    servers: List<Source>,
    localFilesSource: Source?,
    localFilesFolders: List<LocalFilesFolderEntity>,
    localFilesFolderHealth: Map<String, Boolean>,
    singletonWebSources: List<Source>,
    serverVersions: Map<String, String>,
    libraryItemsBySource: Map<String, List<LibraryUiItem>>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
    expandedServers: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onNavigateToAddSource: (AddSourceBackend, String?) -> Unit,
    onNavigateToAddLocalFolder: () -> Unit,
    onOpenReadaloudMatches: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onRemoveLocalFilesSource: () -> Unit,
    onSetLibraryVisible: (String, String, Boolean) -> Unit,
    onReorderLibraries: (String, List<String>) -> Unit,
) {
    SettingsSectionHeader("Sources")
    servers.filter {
        it.serverType == ServerType.AUDIOBOOKSHELF && it.type == SourceType.ABS
    }.forEach { server ->
        ServerRow(
            server = server,
            isExpanded = expandedServers[server.id] == true,
            onToggleExpanded = {
                expandedServers[server.id] = expandedServers[server.id] != true
            },
            onRemove = { onRemoveServer(server.id) },
            serverVersion = serverVersions[server.id],
            libraryItems = libraryItemsBySource[server.id].orEmpty(),
            summary = readaloudSummaries[server.id],
            onSetLibraryVisible = { libraryId, visible ->
                onSetLibraryVisible(server.id, libraryId, visible)
            },
            onReorderLibraries = { orderedIds ->
                onReorderLibraries(server.id, orderedIds)
            },
            onOpenReadaloudMatches = { onOpenReadaloudMatches(server.id) },
        )
    }
    localFilesSource?.let { lfs ->
        LocalFilesSourceRow(
            source = lfs,
            folders = localFilesFolders,
            folderHealth = localFilesFolderHealth,
            libraryItems = libraryItemsBySource[lfs.id].orEmpty(),
            isExpanded = expandedServers[lfs.id] == true,
            onToggleExpanded = {
                expandedServers[lfs.id] = expandedServers[lfs.id] != true
            },
            onAddFolder = onNavigateToAddLocalFolder,
            onRemoveFolder = onRemoveLocalFolder,
            onRemoveSource = onRemoveLocalFilesSource,
            onSetLibraryVisible = { libraryId, visible ->
                onSetLibraryVisible(lfs.id, libraryId, visible)
            },
            onReorderLibraries = { orderedIds ->
                onReorderLibraries(lfs.id, orderedIds)
            },
        )
    }
    singletonWebSources.forEach { source ->
        val descriptor = WebSourceDescriptors.forType(source.type) ?: return@forEach
        SingletonWebSourceRow(
            source = source,
            descriptor = descriptor,
            libraryItems = libraryItemsBySource[source.id].orEmpty(),
            isExpanded = expandedServers[source.id] == true,
            onToggleExpanded = {
                expandedServers[source.id] = expandedServers[source.id] != true
            },
            onSetLibraryVisible = { libraryId, visible ->
                onSetLibraryVisible(source.id, libraryId, visible)
            },
            onReorderLibraries = { orderedIds ->
                onReorderLibraries(source.id, orderedIds)
            },
            onRemove = { onRemoveServer(source.id) },
        )
    }
    Button(
        onClick = { onNavigateToAddSource(AddSourceBackend.AUDIOBOOKSHELF, null) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Add source")
    }
}

/**
 * One Audiobookshelf server entry in the Settings list. Swipe-to-remove wrapper + collapsed
 * header (chevron + type label + username/url/version + Active pill) + expandable body revealing
 * ABS library toggles.
 */
@Composable
internal fun ServerRow(
    server: Source,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    serverVersion: String?,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val username = server.username.takeIf { it.isNotEmpty() }
    val subtitle = buildString {
        if (username != null) {
            append(username)
            append(" · ")
        }
        append(server.url.value)
        if (serverVersion != null) {
            append(" · v")
            append(serverVersion)
        }
    }
    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemove,
        headlineContent = { Text(server.serverType.label) },
        supportingContent = { Text(subtitle) },
        trailingContent = if (server.isActive) {
            { Text("Active", style = MaterialTheme.typography.labelSmall) }
        } else null,
    ) {
        ServerSettingsExpansion(
            server = server,
            libraryItems = libraryItems,
            summary = summary,
            onSetLibraryVisible = onSetLibraryVisible,
            onReorderLibraries = onReorderLibraries,
            onOpenReadaloudMatches = onOpenReadaloudMatches,
        )
    }
}

/**
 * The body revealed when a server row is expanded. ABS servers show their library visibility
 * switches; Storyteller Services would show a readaloud-matches summary — but Storyteller no
 * longer appears in the Sources list (it moved under the Readaloud drill-in), so the branch is
 * retained here only for the pinning test that exercises the expansion in isolation.
 */
@Composable
internal fun ServerSettingsExpansion(
    server: Source,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val transparentColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(modifier = Modifier.fillMaxWidth()) {
        when (server.serverType) {
            ServerType.AUDIOBOOKSHELF -> {
                if (libraryItems.isEmpty()) {
                    ExpansionNote("No libraries found.")
                } else {
                    ReorderableLibraryList(
                        items = libraryItems,
                        onSetLibraryVisible = onSetLibraryVisible,
                        onReorder = onReorderLibraries,
                    )
                }
            }
            ServerType.STORYTELLER_SERVICE -> {
                ExpansionHeader("Readaloud matches")
                val counts = summary ?: ReadaloudMatchSummary(0, 0, 0, 0)
                ListItem(
                    colors = transparentColors,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .clickable { onOpenReadaloudMatches() },
                    headlineContent = { Text("Review & match readalouds") },
                    supportingContent = {
                        Text(
                            "${counts.unmatchedCount} unmatched · " +
                                "${counts.suggestedCount} suggested · " +
                                "${counts.partiallyMatchedCount} partially matched · " +
                                "${counts.matchedCount} matched",
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
private fun ExpansionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ExpansionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    )
}

/**
 * Sources-list row for the singleton LocalFiles Source. Mirrors [ServerRow]'s chevron-expand
 * shape so the two Source types read as siblings: header + collapsed summary; expanding drops
 * down the configured folders with per-folder revocation warnings and a per-folder / whole-source
 * removal path. Adding another folder goes through the standard Add-source picker.
 */
@Composable
internal fun LocalFilesSourceRow(
    source: Source,
    folders: List<LocalFilesFolderEntity>,
    folderHealth: Map<String, Boolean>,
    libraryItems: List<LibraryUiItem>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRemoveSource: () -> Unit,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
) {
    var pendingFolderRemoval by remember { mutableStateOf<LocalFilesFolderEntity?>(null) }
    var pendingSourceRemoval by remember { mutableStateOf(false) }
    val unhealthyCount = folders.count { folderHealth[it.treeUri] == false }

    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemoveSource,
        headerTestTag = "LocalFilesSourceRow",
        headlineContent = { Text("Local files") },
        supportingContent = {
            val folderWord = if (folders.size == 1) "folder" else "folders"
            val summary = buildString {
                append("${folders.size} $folderWord on this device")
                if (unhealthyCount > 0) append(" · $unhealthyCount need attention")
            }
            Text(summary)
        },
        trailingContent = if (unhealthyCount > 0) {
            {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Some folders need attention",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else null,
    ) {
        ListItem(
                    modifier = Modifier
                        .clickable(onClick = onAddFolder)
                        .testTag("LocalFilesSourceRow.AddFolder"),
                    leadingContent = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text("Add folder", color = MaterialTheme.colorScheme.primary)
                    },
                )
                if (folders.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("No folders yet") },
                        supportingContent = {
                            Text("Tap \"Add folder\" above to pick a folder to monitor.")
                        },
                    )
                } else {
                    // Folders and libraries are 1:1 for Local Files. Drive iteration by the
                    // libraryItems list (already ordered per the user's saved reorder), then look
                    // up each folder by libraryId. Any folder without a matching library still
                    // renders — falls back to its natural position at the end.
                    val foldersByLibraryId = folders.associateBy { it.libraryId }
                    // Each row carries the index of its item within [libraryItems] so the reorder
                    // swap lands on the right pair even when a library has no matching folder yet
                    // (or vice versa) during the brief window between DAO flow emissions.
                    val orderedFolders = buildList<Triple<LocalFilesFolderEntity, LibraryUiItem?, Int>> {
                        val consumed = mutableSetOf<String>()
                        libraryItems.forEachIndexed { libraryIndex, item ->
                            foldersByLibraryId[item.library.id]?.let { folder ->
                                add(Triple(folder, item, libraryIndex))
                                consumed += folder.treeUri
                            }
                        }
                        folders.forEach { folder ->
                            if (folder.treeUri !in consumed) add(Triple(folder, null, -1))
                        }
                    }
                    orderedFolders.forEach { (folder, item, libraryIndex) ->
                        val isHealthy = folderHealth[folder.treeUri] != false
                        ListItem(
                            modifier = Modifier.testTag("LocalFilesFolder.${folder.treeUri}"),
                            leadingContent = {
                                if (!isHealthy) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = "Permission revoked",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            headlineContent = { Text(folder.displayName) },
                            supportingContent = {
                                Text(
                                    if (isHealthy) folder.treeUri else "Permission revoked — remove and re-add",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isHealthy) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item != null && libraryItems.size > 1) {
                                        IconButton(
                                            onClick = {
                                                onReorderLibraries(libraryItems.idsWithSwap(libraryIndex, libraryIndex - 1))
                                            },
                                            enabled = libraryIndex > 0,
                                        ) {
                                            Icon(
                                                Icons.Filled.KeyboardArrowUp,
                                                contentDescription = "Move ${folder.displayName} up",
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                onReorderLibraries(libraryItems.idsWithSwap(libraryIndex, libraryIndex + 1))
                                            },
                                            enabled = libraryIndex < libraryItems.lastIndex,
                                        ) {
                                            Icon(
                                                Icons.Filled.KeyboardArrowDown,
                                                contentDescription = "Move ${folder.displayName} down",
                                            )
                                        }
                                    }
                                    if (item != null) {
                                        Switch(
                                            checked = item.isVisible,
                                            onCheckedChange = { visible ->
                                                onSetLibraryVisible(item.library.id, visible)
                                            },
                                            enabled = item.switchEnabled,
                                        )
                                    }
                                    IconButton(onClick = { pendingFolderRemoval = folder }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                                    }
                                }
                            },
                        )
                    }
                }
        TextButton(
            onClick = { pendingSourceRemoval = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Remove Local Files source", color = MaterialTheme.colorScheme.error)
        }
    }

    pendingFolderRemoval?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingFolderRemoval = null },
            title = { Text("Remove folder?") },
            text = {
                Text(
                    "\"${folder.displayName}\" will be removed and any books that came from it " +
                        "will be deleted from this device. Books shared with another configured " +
                        "folder are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveFolder(folder.treeUri)
                    pendingFolderRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFolderRemoval = null }) { Text("Cancel") }
            },
        )
    }
    if (pendingSourceRemoval) {
        AlertDialog(
            onDismissRequest = { pendingSourceRemoval = false },
            title = { Text("Remove Local Files source?") },
            text = { Text("Every configured folder and every locally-stored book will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveSource()
                    pendingSourceRemoval = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSourceRemoval = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Sources-list row for a singleton web source (Chitanka, Gutenberg, and any future
 * `WebSourceDescriptor.isSingleton == true` source without bespoke settings UI). Zero-config;
 * the expanded body exposes the per-library visibility+order editor. Removal is via the shared
 * end-to-start swipe gesture, matching every other configured-source row.
 *
 * Renders header text from the [descriptor] so adding a new source needs no new composable —
 * just a `WebSourceDescriptor object` and its `@IntoSet` binding (ADR 0044).
 */
@Composable
internal fun SingletonWebSourceRow(
    source: Source,
    descriptor: WebSourceDescriptor,
    libraryItems: List<LibraryUiItem>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onRemove: () -> Unit,
) {
    val supportText = descriptor.supportingHosts ?: descriptor.subtitle
    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemove,
        headerTestTag = "${descriptor.type.name}SourceRow",
        headlineContent = { Text(descriptor.displayName) },
        supportingContent = supportText?.let { { Text(it) } },
    ) {
        if (libraryItems.isNotEmpty()) {
            ReorderableLibraryList(
                items = libraryItems,
                onSetLibraryVisible = onSetLibraryVisible,
                onReorder = onReorderLibraries,
            )
        }
    }
}
