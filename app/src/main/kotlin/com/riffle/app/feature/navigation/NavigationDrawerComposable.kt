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
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.app.BuildConfig
import com.riffle.app.ui.source.SourceIcon
import com.riffle.app.ui.source.localizedSourceDisplayName as localizedDescriptorDisplayName
import com.riffle.app.ui.source.localizedSourceSubtitle as localizedDescriptorSubtitle
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
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
                label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_downloads)) },
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                selected = false,
                onClick = onDownloadsSelected,
            )
        }
        NavigationDrawerItem(
            label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_settings)) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = onSettingsSelected,
        )
        val uriHandler = LocalUriHandler.current
        Text(
            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_support_support_on_ko_fi),
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
            text = androidx.compose.ui.res.stringResource(
                com.riffle.app.R.string.ui_app_version,
                BuildConfig.VERSION_NAME,
                sha?.let { " ($it)" } ?: "",
            ),
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

    Box(modifier = Modifier
        .fillMaxWidth()
        .onSizeChanged { headerWidth = with(density) { it.width.toDp() } }
    ) {
        ListItem(
            leadingContent = activeServer?.let { server ->
                { SourceRowIcon(server = server) }
            },
            headlineContent = {
                val name = activeServer?.let { localizedSourceDisplayName(it) }
                    ?: androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_source)
                val username = activeServer
                    ?.takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                    ?.username?.takeIf { it.isNotEmpty() }
                if (username != null) {
                    AutoShrinkingSingleLineText(
                        text = buildAnnotatedString {
                            append(name)
                            append(" ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("[$username]")
                            }
                        },
                    )
                } else {
                    AutoShrinkingSingleLineText(
                        text = name,
                    )
                }
            },
            supportingContent = {
                val support = activeServer?.let {
                    localizedSourceSwitcherSubtitle(source = it, version = activeVersion)
                }
                if (support != null) {
                    AutoShrinkingSingleLineText(
                        text = support,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Icon(
                    imageVector = if (switcherExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_toggle_source_switcher),
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
                DropdownMenuItem(
                    text = {
                        Column {
                            val displayName = localizedSourceDisplayName(server)
                            val username = server
                                .takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                                ?.username?.takeIf { it.isNotEmpty() }
                            if (username != null) {
                                AutoShrinkingSingleLineText(
                                    text = buildAnnotatedString {
                                        append(displayName)
                                        append(" ")
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                            append("[$username]")
                                        }
                                    },
                                )
                            } else {
                                AutoShrinkingSingleLineText(
                                    text = displayName,
                                )
                            }
                            val support = localizedSourceSwitcherSubtitle(
                                source = server,
                                version = serverVersions[server.id],
                            )
                            if (support != null) {
                                AutoShrinkingSingleLineText(
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
                            Icon(Icons.Default.Check, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_active_source))
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

@Composable
private fun AutoShrinkingSingleLineText(
    text: String,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 12.sp,
) {
    AutoShrinkingSingleLineText(
        text = AnnotatedString(text),
        color = color,
        style = style,
        minFontSize = minFontSize,
    )
}

@Composable
private fun AutoShrinkingSingleLineText(
    text: AnnotatedString,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    minFontSize: TextUnit = 12.sp,
) {
    var resizedStyle by remember(text, style, minFontSize) { mutableStateOf(style) }
    Text(
        text = text,
        color = color,
        style = resizedStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            val nextSize = nextOverflowFontSize(
                currentSize = resizedStyle.fontSize,
                minFontSize = minFontSize,
                hasVisualOverflow = result.hasVisualOverflow,
            )
            if (nextSize != null) {
                resizedStyle = resizedStyle.copy(fontSize = nextSize)
            }
        },
    )
}

internal fun nextOverflowFontSize(
    currentSize: TextUnit,
    minFontSize: TextUnit,
    hasVisualOverflow: Boolean,
): TextUnit? {
    if (!hasVisualOverflow || currentSize == TextUnit.Unspecified) return null
    if (currentSize.value <= minFontSize.value) return null
    val nextValue = maxOf(minFontSize.value, currentSize.value * 0.9f)
    return nextValue.sp.takeIf { it != currentSize }
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
internal fun buildSupportingLine(host: String?, version: String?): String? {
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
internal fun sourceDisplayName(source: Source): String =
    if (source.type == SourceType.ABS) source.serverType.label
    else WebSourceDescriptors.forTypeOrError(source.type).displayName

@Composable
private fun localizedSourceDisplayName(source: Source): String =
    if (source.type == SourceType.ABS) source.serverType.label
    else localizedDescriptorDisplayName(WebSourceDescriptors.forTypeOrError(source.type))

/**
 * Subtitle for the source-switcher row. Sources with a network host render their configured
 * address on every row; everything else falls back to the descriptor's static subtitle. Gated on
 * [WebSourceDescriptor.hasNetworkHost] so a new credentialed source drops in without an edit here.
 */
internal fun sourceSwitcherSubtitle(source: Source, version: String?): String? {
    val descriptor = WebSourceDescriptors.forType(source.type) ?: return null
    return if (descriptor.hasNetworkHost) {
        buildSupportingLine(source.url.authority(), version)
    } else {
        descriptor.subtitle
    }
}

@Composable
private fun localizedSourceSwitcherSubtitle(source: Source, version: String?): String? {
    val descriptor = WebSourceDescriptors.forType(source.type) ?: return null
    return if (descriptor.hasNetworkHost) {
        buildSupportingLine(source.url.authority(), version)
    } else {
        localizedDescriptorSubtitle(descriptor)
    }
}
