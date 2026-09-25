package com.riffle.feature.source.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.LibraryUiItem
import com.riffle.feature.settings.ReadaloudMatchSummary
import com.riffle.feature.settings.idsWithSwap
import com.riffle.feature.settings.label
import com.riffle.feature.source.ui.ConfirmDestructiveDialog
import com.riffle.feature.source.ui.MaterialGlyphs
import com.riffle.feature.source.ui.SourceIcon
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_active
import com.riffle.feature.source.ui.generated.resources.ui_add_folder
import com.riffle.feature.source.ui.generated.resources.ui_add_source
import com.riffle.feature.source.ui.generated.resources.ui_local_files
import com.riffle.feature.source.ui.generated.resources.ui_local_folders_summary
import com.riffle.feature.source.ui.generated.resources.ui_move_named_item_down
import com.riffle.feature.source.ui.generated.resources.ui_move_named_item_up
import com.riffle.feature.source.ui.generated.resources.ui_need_attention_count
import com.riffle.feature.source.ui.generated.resources.ui_no_folders_yet
import com.riffle.feature.source.ui.generated.resources.ui_no_libraries_found
import com.riffle.feature.source.ui.generated.resources.ui_permission_revoked
import com.riffle.feature.source.ui.generated.resources.ui_permission_revoked_remove_and_readd
import com.riffle.feature.source.ui.generated.resources.ui_readaloud_match_counts
import com.riffle.feature.source.ui.generated.resources.ui_readaloud_matches
import com.riffle.feature.source.ui.generated.resources.ui_remove_folder
import com.riffle.feature.source.ui.generated.resources.ui_remove_folder_3
import com.riffle.feature.source.ui.generated.resources.ui_remove_folder_explanation
import com.riffle.feature.source.ui.generated.resources.ui_review_and_match_readalouds
import com.riffle.feature.source.ui.generated.resources.ui_some_folders_need_attention
import com.riffle.feature.source.ui.generated.resources.ui_tap_add_folder_above_to_pick_a_folder_to_monitor
import com.riffle.feature.source.ui.localizedSourceDisplayName
import com.riffle.feature.source.ui.localizedSourceSubtitle
import org.jetbrains.compose.resources.stringResource

/**
 * "Sources" list on the main Settings screen. Renders one row per configured browsable source
 * (Audiobookshelf servers, Local Files, Chitanka) plus an "Add source" button. Each row expands
 * inline to reveal its library visibility+order editor and per-source management controls.
 *
 * Storyteller Services do NOT appear here — they live under the collapsed Readaloud entry, which
 * is a Service (not a Source) per ADR 0024.
 *
 * Shared by both hosts; there is no Android copy. The caller renders its own section header
 * before this, because Android's header carries a `HorizontalDivider` the iOS settings list does
 * not use — the list itself is what had to stop existing twice.
 */
@Composable
fun SourcesSection(
    servers: List<Source>,
    localFilesSource: Source?,
    localFilesFolders: List<LocalFilesFolderEntity>,
    localFilesFolderHealth: Map<String, Boolean>,
    singletonWebSources: List<Source>,
    sourceVersions: Map<String, String>,
    libraryItemsBySource: Map<String, List<LibraryUiItem>>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
    expandedSources: SnapshotStateMap<String, Boolean>,
    onNavigateToAddSourcePicker: () -> Unit,
    onNavigateToAddLocalFolder: () -> Unit,
    onOpenReadaloudMatches: (String) -> Unit,
    onRemoveSource: (String) -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onRemoveLocalFilesSource: () -> Unit,
    onSetLibraryVisible: (String, String, Boolean) -> Unit,
    onReorderLibraries: (String, List<String>) -> Unit,
) {
    // Credentialed multi-instance sources: any Source whose [WebSourceDescriptor] declares
    // hasCredentials=true && isSingleton=false renders here. Storyteller Services are excluded
    // because they live under the Readaloud drill-in (ADR 0024) — the exclusion is by
    // `serverType`, not by SourceType, because Storyteller currently shares SourceType.ABS with
    // Audiobookshelf. Once #441 splits Storyteller into its own SourceType the serverType clause
    // drops out. A new credentialed source (Komga, Calibre-Web, …) drops in without an edit
    // here; it just needs a `WebSourceDescriptor` with `hasCredentials=true, isSingleton=false`.
    servers.filter { source ->
        val descriptor = WebSourceDescriptors.forType(source.type)
        descriptor != null &&
            descriptor.hasCredentials &&
            !descriptor.isSingleton &&
            source.serverType != ServerType.STORYTELLER_SERVICE
    }.forEach { source ->
        val descriptor = WebSourceDescriptors.forType(source.type) ?: return@forEach
        ConfiguredSourceRow(
            source = source,
            descriptor = descriptor,
            isExpanded = expandedSources[source.id] == true,
            onToggleExpanded = {
                expandedSources[source.id] = expandedSources[source.id] != true
            },
            onRemove = { onRemoveSource(source.id) },
            sourceVersion = sourceVersions[source.id],
            libraryItems = libraryItemsBySource[source.id].orEmpty(),
            summary = readaloudSummaries[source.id],
            onSetLibraryVisible = { libraryId, visible ->
                onSetLibraryVisible(source.id, libraryId, visible)
            },
            onReorderLibraries = { orderedIds ->
                onReorderLibraries(source.id, orderedIds)
            },
            onOpenReadaloudMatches = { onOpenReadaloudMatches(source.id) },
        )
    }
    localFilesSource?.let { lfs ->
        LocalFilesSourceRow(
            source = lfs,
            folders = localFilesFolders,
            folderHealth = localFilesFolderHealth,
            libraryItems = libraryItemsBySource[lfs.id].orEmpty(),
            isExpanded = expandedSources[lfs.id] == true,
            onToggleExpanded = {
                expandedSources[lfs.id] = expandedSources[lfs.id] != true
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
            isExpanded = expandedSources[source.id] == true,
            onToggleExpanded = {
                expandedSources[source.id] = expandedSources[source.id] != true
            },
            onSetLibraryVisible = { libraryId, visible ->
                onSetLibraryVisible(source.id, libraryId, visible)
            },
            onReorderLibraries = { orderedIds ->
                onReorderLibraries(source.id, orderedIds)
            },
            onRemove = { onRemoveSource(source.id) },
        )
    }
    Button(
        onClick = onNavigateToAddSourcePicker,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(TestTags.SOURCES_ADD_SOURCE),
    ) {
        Text(stringResource(Res.string.ui_add_source))
    }
}

/**
 * One credentialed source entry in the Settings list: swipe-to-remove wrapper + collapsed header
 * (chevron + type label + username/url/version + Active pill) + expandable body revealing the
 * library toggles.
 */
@Composable
fun ConfiguredSourceRow(
    source: Source,
    descriptor: WebSourceDescriptor,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    sourceVersion: String?,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val subtitle = configuredSourceSubtitle(source, descriptor, sourceVersion)
    // Headline: for the ABS descriptor we keep the ABS/Storyteller product label because it's the
    // one credentialed SourceType whose displayName ("Audiobookshelf") isn't the whole story —
    // Storyteller Services would collapse into "Audiobookshelf" in the row title otherwise. Every
    // other credentialed source (Komga, Calibre-Web, …) uses its descriptor's displayName
    // directly. Once #441 splits Storyteller into its own SourceType this branch collapses.
    val headline = if (source.type == SourceType.ABS) {
        source.serverType.label
    } else {
        localizedSourceDisplayName(descriptor)
    }
    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemove,
        headerTestTag = "ConfiguredSourceRow.${source.id}",
        leadingIcon = { SourceIcon(source = source, size = 32.dp) },
        headlineContent = { Text(headline) },
        supportingContent = { Text(subtitle) },
        trailingContent = if (source.isActive) {
            { Text(stringResource(Res.string.ui_active), style = MaterialTheme.typography.labelSmall) }
        } else {
            null
        },
    ) {
        SourceSettingsExpansion(
            source = source,
            libraryItems = libraryItems,
            summary = summary,
            onSetLibraryVisible = onSetLibraryVisible,
            onReorderLibraries = onReorderLibraries,
            onOpenReadaloudMatches = onOpenReadaloudMatches,
        )
    }
}

/**
 * "username · https://host · v2.15.1" — the collapsed subtitle of a credentialed source row.
 *
 * `internal` and top-level rather than inline in the composable so `SourcesSectionTest` can pin
 * it: the server version was collected on both hosts and only ever rendered on Android, and the
 * iOS Settings screen showed the bare authority instead of the full URL.
 */
internal fun configuredSourceSubtitle(
    source: Source,
    descriptor: WebSourceDescriptor,
    sourceVersion: String?,
): String {
    val username = source.username.takeIf { it.isNotEmpty() }
    return buildString {
        if (descriptor.hasCredentials && username != null) {
            append(username)
            append(" · ")
        }
        if (descriptor.hasNetworkHost) {
            append(source.url.value)
            if (sourceVersion != null) {
                append(" · v")
                append(sourceVersion)
            }
        }
    }
}

/**
 * The body revealed when a source row is expanded. Audiobookshelf sources show their library
 * visibility switches; Storyteller Services would show a readaloud-matches summary — but
 * Storyteller no longer appears in the Sources list (it moved under the Readaloud drill-in), so
 * the branch is retained here only for the pinning test that exercises the expansion in isolation.
 */
@Composable
fun SourceSettingsExpansion(
    source: Source,
    libraryItems: List<LibraryUiItem>,
    summary: ReadaloudMatchSummary?,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onOpenReadaloudMatches: () -> Unit,
) {
    val transparentColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(modifier = Modifier.fillMaxWidth()) {
        when (source.serverType) {
            ServerType.AUDIOBOOKSHELF -> {
                if (libraryItems.isEmpty()) {
                    ExpansionNote(stringResource(Res.string.ui_no_libraries_found))
                } else {
                    ReorderableLibraryList(
                        items = libraryItems,
                        onSetLibraryVisible = onSetLibraryVisible,
                        onReorder = onReorderLibraries,
                    )
                }
            }
            ServerType.STORYTELLER_SERVICE -> {
                ExpansionHeader(stringResource(Res.string.ui_readaloud_matches))
                val counts = summary ?: ReadaloudMatchSummary(0, 0, 0, 0)
                ListItem(
                    colors = transparentColors,
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .testTag(TestTags.SOURCE_SETTINGS_READALOUD_MATCHES)
                        .clickable { onOpenReadaloudMatches() },
                    headlineContent = { Text(stringResource(Res.string.ui_review_and_match_readalouds)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                Res.string.ui_readaloud_match_counts,
                                counts.unmatchedCount,
                                counts.suggestedCount,
                                counts.partiallyMatchedCount,
                                counts.matchedCount,
                            ),
                        )
                    },
                    trailingContent = {
                        Icon(MaterialGlyphs.KeyboardArrowRight, contentDescription = null)
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
 * Sources-list row for the singleton LocalFiles Source. Mirrors [ConfiguredSourceRow]'s
 * chevron-expand shape so the two Source types read as siblings: header + collapsed summary;
 * expanding drops down the configured folders with per-folder revocation warnings and a
 * per-folder / whole-source removal path.
 */
@Composable
fun LocalFilesSourceRow(
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
    val unhealthyCount = folders.count { folderHealth[it.treeUri] == false }

    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemoveSource,
        headerTestTag = "LocalFilesSourceRow",
        leadingIcon = { SourceIcon(source = source, size = 32.dp) },
        headlineContent = { Text(stringResource(Res.string.ui_local_files)) },
        supportingContent = {
            val parts = mutableListOf(stringResource(Res.string.ui_local_folders_summary, folders.size))
            if (unhealthyCount > 0) parts += stringResource(Res.string.ui_need_attention_count, unhealthyCount)
            Text(parts.joinToString(" · "))
        },
        trailingContent = if (unhealthyCount > 0) {
            {
                Icon(
                    RiffleIcons.Warning,
                    contentDescription = stringResource(Res.string.ui_some_folders_need_attention),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            null
        },
    ) {
        ListItem(
            modifier = Modifier
                .clickable(onClick = onAddFolder)
                .testTag(TestTags.LOCAL_FILES_ADD_FOLDER),
            leadingContent = {
                Icon(
                    MaterialGlyphs.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text(stringResource(Res.string.ui_add_folder), color = MaterialTheme.colorScheme.primary)
            },
        )
        if (folders.isEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.ui_no_folders_yet)) },
                supportingContent = {
                    Text(stringResource(Res.string.ui_tap_add_folder_above_to_pick_a_folder_to_monitor))
                },
            )
        } else {
            localFilesFolderRows(folders, libraryItems).forEach { row ->
                LocalFilesFolderRow(
                    row = row,
                    isHealthy = folderHealth[row.folder.treeUri] != false,
                    libraryCount = libraryItems.size,
                    lastLibraryIndex = libraryItems.lastIndex,
                    canRemove = folders.size > 1,
                    onRequestRemove = { pendingFolderRemoval = row.folder },
                    onSetLibraryVisible = onSetLibraryVisible,
                    onReorderLibraries = { from, to -> onReorderLibraries(libraryItems.idsWithSwap(from, to)) },
                )
            }
        }
    }

    pendingFolderRemoval?.let { folder ->
        ConfirmDestructiveDialog(
            title = stringResource(Res.string.ui_remove_folder),
            message = stringResource(Res.string.ui_remove_folder_explanation, folder.displayName),
            confirmLabel = stringResource(Res.string.ui_remove_folder_3),
            onConfirm = { onRemoveFolder(folder.treeUri) },
            onDismiss = { pendingFolderRemoval = null },
            testTag = TestTags.LOCAL_FILES_CONFIRM_REMOVE_FOLDER,
        )
    }
}

/**
 * One rendered folder row: the folder, the library it maps to (if the DAO flows have caught up),
 * and that library's index within the user's saved order.
 */
internal data class LocalFilesFolderRowModel(
    val folder: LocalFilesFolderEntity,
    val libraryItem: LibraryUiItem?,
    val libraryIndex: Int,
)

/**
 * Orders the folder rows by the user's saved library order.
 *
 * Folders and libraries are 1:1 for Local Files, so iteration is driven by [libraryItems] (already
 * ordered) and each folder is looked up by `libraryId`. A folder with no matching library still
 * renders — it falls back to its natural position at the end with `libraryIndex = -1`, which is
 * what keeps a row visible during the brief window between DAO flow emissions.
 *
 * Extracted from the composable so the ordering rule is assertable without a UI test.
 */
internal fun localFilesFolderRows(
    folders: List<LocalFilesFolderEntity>,
    libraryItems: List<LibraryUiItem>,
): List<LocalFilesFolderRowModel> {
    val foldersByLibraryId = folders.associateBy { it.libraryId }
    return buildList {
        val consumed = mutableSetOf<String>()
        libraryItems.forEachIndexed { libraryIndex, item ->
            foldersByLibraryId[item.library.id]?.let { folder ->
                add(LocalFilesFolderRowModel(folder, item, libraryIndex))
                consumed += folder.treeUri
            }
        }
        folders.forEach { folder ->
            if (folder.treeUri !in consumed) add(LocalFilesFolderRowModel(folder, null, -1))
        }
    }
}

@Composable
private fun LocalFilesFolderRow(
    row: LocalFilesFolderRowModel,
    isHealthy: Boolean,
    libraryCount: Int,
    lastLibraryIndex: Int,
    canRemove: Boolean,
    onRequestRemove: () -> Unit,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (from: Int, to: Int) -> Unit,
) {
    val folder = row.folder
    val item = row.libraryItem
    ListItem(
        modifier = Modifier.testTag(TestTags.localFilesFolder(folder.treeUri)),
        leadingContent = {
            if (!isHealthy) {
                Icon(
                    RiffleIcons.Warning,
                    contentDescription = stringResource(Res.string.ui_permission_revoked),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        headlineContent = { Text(folder.displayName) },
        supportingContent = {
            Text(
                if (isHealthy) folder.treeUri else stringResource(Res.string.ui_permission_revoked_remove_and_readd),
                style = MaterialTheme.typography.bodySmall,
                color = if (isHealthy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item != null && libraryCount > 1) {
                    IconButton(
                        onClick = { onReorderLibraries(row.libraryIndex, row.libraryIndex - 1) },
                        enabled = row.libraryIndex > 0,
                    ) {
                        Icon(
                            MaterialGlyphs.KeyboardArrowUp,
                            contentDescription = stringResource(Res.string.ui_move_named_item_up, folder.displayName),
                        )
                    }
                    IconButton(
                        onClick = { onReorderLibraries(row.libraryIndex, row.libraryIndex + 1) },
                        enabled = row.libraryIndex < lastLibraryIndex,
                    ) {
                        Icon(
                            MaterialGlyphs.KeyboardArrowDown,
                            contentDescription = stringResource(Res.string.ui_move_named_item_down, folder.displayName),
                        )
                    }
                }
                if (item != null) {
                    Switch(
                        checked = item.isVisible,
                        onCheckedChange = { visible -> onSetLibraryVisible(item.library.id, visible) },
                        enabled = item.switchEnabled,
                    )
                }
                IconButton(
                    onClick = onRequestRemove,
                    enabled = canRemove,
                    modifier = Modifier.testTag(TestTags.localFilesFolderRemove(folder.treeUri)),
                ) {
                    Icon(MaterialGlyphs.Delete, contentDescription = stringResource(Res.string.ui_remove_folder_3))
                }
            }
        },
    )
}

/**
 * Sources-list row for a singleton web source (Chitanka, Gutenberg, and any future
 * `WebSourceDescriptor.isSingleton == true` source without bespoke settings UI). Zero-config;
 * the expanded body exposes the per-library visibility+order editor. Removal is via the shared
 * end-to-start swipe gesture, matching every other configured-source row.
 *
 * Renders header text from the [descriptor] so adding a new source needs no new composable —
 * just a `WebSourceDescriptor object` and its `@IntoSet` binding (ADR 0053).
 */
@Composable
fun SingletonWebSourceRow(
    source: Source,
    descriptor: WebSourceDescriptor,
    libraryItems: List<LibraryUiItem>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorderLibraries: (orderedLibraryIds: List<String>) -> Unit,
    onRemove: () -> Unit,
) {
    val supportText = descriptor.supportingHosts ?: localizedSourceSubtitle(descriptor)
    ExpandableSourceRow(
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        onRemove = onRemove,
        headerTestTag = "${descriptor.type.name}SourceRow",
        leadingIcon = { SourceIcon(source = source, size = 32.dp) },
        headlineContent = { Text(localizedSourceDisplayName(descriptor)) },
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

/**
 * The expanded-source "Enabled libraries" list. Each row carries up/down controls that move the
 * library within the source's order, plus the visibility switch on the trailing edge.
 *
 * Reordering uses explicit move buttons rather than a drag gesture: this list lives inside the
 * Settings vertical scroll, where a long-press-drag competes with the page scroll and drops are
 * lost. A tap can't be stolen by the scroll, so each move reliably persists. On a move we hand the
 * full new id order to [onReorder]; the displayed order then follows [items] from the ViewModel.
 */
@Composable
fun ReorderableLibraryList(
    items: List<LibraryUiItem>,
    onSetLibraryVisible: (libraryId: String, visible: Boolean) -> Unit,
    onReorder: (orderedLibraryIds: List<String>) -> Unit,
) {
    val transparentColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    items.forEachIndexed { index, item ->
        ListItem(
            colors = transparentColors,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
            headlineContent = { Text(item.library.name) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (items.size > 1) {
                        IconButton(
                            onClick = { onReorder(items.idsWithSwap(index, index - 1)) },
                            enabled = index > 0,
                            modifier = Modifier.testTag(TestTags.reorderableLibraryUp(item.library.id)),
                        ) {
                            Icon(
                                MaterialGlyphs.KeyboardArrowUp,
                                contentDescription = stringResource(
                                    Res.string.ui_move_named_item_up,
                                    item.library.name,
                                ),
                            )
                        }
                        IconButton(
                            onClick = { onReorder(items.idsWithSwap(index, index + 1)) },
                            enabled = index < items.lastIndex,
                            modifier = Modifier.testTag(TestTags.reorderableLibraryDown(item.library.id)),
                        ) {
                            Icon(
                                MaterialGlyphs.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    Res.string.ui_move_named_item_down,
                                    item.library.name,
                                ),
                            )
                        }
                    }
                    Switch(
                        checked = item.isVisible,
                        onCheckedChange = { visible -> onSetLibraryVisible(item.library.id, visible) },
                        enabled = item.switchEnabled,
                    )
                }
            },
        )
    }
}
