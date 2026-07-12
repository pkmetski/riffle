package com.riffle.app.feature.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.app.BuildConfig
import com.riffle.app.ui.source.SourceIcon
import com.riffle.core.domain.Library
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleNavigationDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    usePermanentDrawer: Boolean = false,
    hidePermanentDrawerPanel: Boolean = false,
    activeServer: Source?,
    allServers: List<Source>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    serverVersions: Map<String, String>,
    showDownloadsLink: Boolean = true,
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onDownloadsSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetBody: @Composable () -> Unit = {
        DrawerSheetContent(
            activeServer = activeServer,
            allServers = allServers,
            visibleLibraries = visibleLibraries,
            activeLibraryId = activeLibraryId,
            serverVersions = serverVersions,
            showDownloadsLink = showDownloadsLink,
            onServerSelected = onServerSelected,
            onLibrarySelected = onLibrarySelected,
            onDownloadsSelected = onDownloadsSelected,
            onSettingsSelected = onSettingsSelected,
        )
    }

    if (usePermanentDrawer) {
        // ADR 0019: Tablet Layout (Expanded ≥ 840dp) replaces the modal drawer with a
        // permanent drawer pinned to the leading edge — no hamburger, no scrim.
        PermanentNavigationDrawer(
            drawerContent = {
                if (!hidePermanentDrawerPanel) {
                    PermanentDrawerSheet(modifier = Modifier.width(280.dp)) { sheetBody() }
                }
            },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) { sheetBody() }
            },
            content = content,
        )
    }
}

@Composable
private fun DrawerSheetContent(
    activeServer: Source?,
    allServers: List<Source>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    serverVersions: Map<String, String>,
    showDownloadsLink: Boolean,
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onDownloadsSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        DrawerHeader(
            activeServer = activeServer,
            allServers = allServers,
            serverVersions = serverVersions,
            onServerSelected = onServerSelected,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            visibleLibraries.forEach { library ->
                NavigationDrawerItem(
                    label = { Text(library.name) },
                    selected = library.id == activeLibraryId,
                    onClick = { onLibrarySelected(library) },
                )
            }
        }
        HorizontalDivider()
        if (showDownloadsLink) {
            NavigationDrawerItem(
                label = { Text("Downloads") },
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                selected = false,
                onClick = onDownloadsSelected,
            )
        }
        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = onSettingsSelected,
        )
        val uriHandler = LocalUriHandler.current
        Text(
            text = "☕ Support on Ko-fi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { uriHandler.openUri("https://ko-fi.com/pkmetski") }
                .padding(top = 8.dp, bottom = 2.dp),
            textAlign = TextAlign.Center,
        )
        val sha = BuildConfig.GIT_SHA.takeIf { it.isNotEmpty() }
        Text(
            text = "Riffle v${BuildConfig.VERSION_NAME}" + (sha?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp, top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawerHeader(
    activeServer: Source?,
    allServers: List<Source>,
    serverVersions: Map<String, String>,
    onServerSelected: (Source) -> Unit,
) {
    val activeVersion = activeServer?.id?.let { serverVersions[it] }
    var switcherExpanded by remember { mutableStateOf(false) }
    var headerWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current

    // Same product type appearing more than once forces the host to appear in the supporting
    // line so users can tell the two instances apart. With a single instance the host is noise.
    // Zero-config web sources (LocalFiles, Chitanka, Gutenberg) have `hasNetworkHost = false` in
    // their [WebSourceDescriptor] — the descriptor is the source of truth so a new credentialed
    // source (Komga) drops in without editing this predicate.
    val activeNeedsHost = activeServer != null &&
        WebSourceDescriptors.forType(activeServer.type)?.hasNetworkHost == true &&
        allServers.count { it.serverType == activeServer.serverType && it.type == activeServer.type } > 1

    Box(modifier = Modifier
        .fillMaxWidth()
        .onSizeChanged { headerWidth = with(density) { it.width.toDp() } }
    ) {
        ListItem(
            leadingContent = activeServer?.let { server ->
                { SourceRowIcon(server = server) }
            },
            headlineContent = {
                val name = activeServer?.let(::sourceDisplayName) ?: "No source"
                val username = activeServer
                    ?.takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                    ?.username?.takeIf { it.isNotEmpty() }
                if (username != null) {
                    Text(
                        buildAnnotatedString {
                            append(name)
                            append(" ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("[$username]")
                            }
                        }
                    )
                } else {
                    Text(name)
                }
            },
            supportingContent = {
                val support = activeServer?.let {
                    sourceSubtitle(
                        source = it,
                        host = if (activeNeedsHost) it.url.authority() else null,
                        version = activeVersion,
                    )
                }
                if (support != null) Text(support)
            },
            trailingContent = {
                Icon(
                    imageVector = if (switcherExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle source switcher",
                )
            },
            modifier = Modifier.clickable { switcherExpanded = !switcherExpanded },
        )
        DropdownMenu(
            expanded = switcherExpanded,
            onDismissRequest = { switcherExpanded = false },
            modifier = if (headerWidth != Dp.Unspecified) Modifier.width(headerWidth) else Modifier,
        ) {
            allServers.forEach { server ->
                val needsHost = WebSourceDescriptors.forType(server.type)?.hasNetworkHost == true &&
                    allServers.count { it.serverType == server.serverType && it.type == server.type } > 1
                DropdownMenuItem(
                    text = {
                        Column {
                            val displayName = sourceDisplayName(server)
                            val username = server
                                .takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                                ?.username?.takeIf { it.isNotEmpty() }
                            if (username != null) {
                                Text(
                                    buildAnnotatedString {
                                        append(displayName)
                                        append(" ")
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                            append("[$username]")
                                        }
                                    }
                                )
                            } else {
                                Text(displayName)
                            }
                            val support = sourceSubtitle(
                                source = server,
                                host = if (needsHost) server.url.authority() else null,
                                version = serverVersions[server.id],
                            )
                            if (support != null) {
                                Text(
                                    text = support,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { SourceRowIcon(server = server) },
                    trailingIcon = {
                        if (server.isActive) {
                            Icon(Icons.Default.Check, contentDescription = "Active source")
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        switcherExpanded = false
                        onServerSelected(server)
                    },
                )
            }
        }
    }
}

/**
 * Leading icon for a source row in the switcher. Network sources render their server's favicon
 * via [SourceIcon] (bundled monogram fallback); LocalFiles keeps its Material Folder treatment
 * unchanged — the switcher had no monogram concept for LocalFiles before this change.
 */
@Composable
private fun SourceRowIcon(server: Source) {
    if (server.type == SourceType.LOCAL_FILES) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    } else {
        SourceIcon(source = server, size = 24.dp)
    }
}

/**
 * Drawer supporting line: `host · version`, `host`, `version`, or null if nothing to show.
 * Storyteller never has a version (the repository returns null for it).
 */
private fun buildSupportingLine(host: String?, version: String?): String? {
    val v = version?.let { "v$it" }
    return when {
        host != null && v != null -> "$host · $v"
        host != null -> host
        v != null -> v
        else -> null
    }
}

/**
 * Display label for the source-switcher header + dropdown. Non-ABS sources have their name in
 * their [com.riffle.core.domain.WebSourceDescriptor]; ABS is per-server (the same SourceType
 * covers Audiobookshelf and Storyteller product servers) so it picks the server-type label.
 */
private fun sourceDisplayName(source: Source): String =
    if (source.type == SourceType.ABS) source.serverType.label
    else WebSourceDescriptors.forTypeOrError(source.type).displayName

/**
 * Subtitle for the source-switcher row. Credentialed sources with a network host (ABS today,
 * Komga tomorrow) render [buildSupportingLine] (host · version); everything else falls back to
 * the descriptor's static subtitle. Gated on [WebSourceDescriptor.hasNetworkHost] so a new
 * credentialed source drops in without an edit here.
 */
private fun sourceSubtitle(source: Source, host: String?, version: String?): String? {
    val descriptor = WebSourceDescriptors.forType(source.type) ?: return null
    return if (descriptor.hasNetworkHost) buildSupportingLine(host, version) else descriptor.subtitle
}
