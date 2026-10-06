package com.riffle.feature.library.ui

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
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.KoFiDrawerButton
import com.riffle.feature.designsystem.RiffleAppIcon
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.shouldShowRiffleSource
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_active_source
import com.riffle.feature.library.ui.generated.resources.ui_app_version_footer
import com.riffle.feature.library.ui.generated.resources.ui_drawer_downloads
import com.riffle.feature.library.ui.generated.resources.ui_drawer_no_source
import com.riffle.feature.library.ui.generated.resources.ui_drawer_riffle
import com.riffle.feature.library.ui.generated.resources.ui_drawer_settings
import com.riffle.feature.library.ui.generated.resources.ui_toggle_source_switcher
import com.riffle.feature.source.ui.SourceIcon
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.source.ui.localizedSourceDisplayName
import com.riffle.feature.source.ui.localizedSourceSubtitle as localizedDescriptorSubtitle
import com.riffle.feature.source.ui.sourceDisplayName

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
    onRiffleSelected: () -> Unit = {},
    isRiffleActive: Boolean = false,
    appVersion: String? = null,
    appSha: String? = null,
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
            onRiffleSelected = onRiffleSelected,
            isRiffleActive = isRiffleActive,
            appVersion = appVersion,
            appSha = appSha,
        )
    }

    if (usePermanentDrawer) {
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
    onRiffleSelected: () -> Unit = {},
    isRiffleActive: Boolean = false,
    appVersion: String? = null,
    appSha: String? = null,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        DrawerHeader(
            activeServer = activeServer,
            allServers = allServers,
            serverVersions = serverVersions,
            onServerSelected = onServerSelected,
            isRiffleActive = isRiffleActive,
            onRiffleSelected = onRiffleSelected,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!isRiffleActive) {
                visibleLibraries.forEach { library ->
                    NavigationDrawerItem(
                        label = { Text(library.name) },
                        selected = library.id == activeLibraryId,
                        onClick = { onLibrarySelected(library) },
                    )
                }
            }
        }
        HorizontalDivider()
        if (showDownloadsLink) {
            NavigationDrawerItem(
                label = { Text(stringResource(Res.string.ui_drawer_downloads)) },
                icon = { Icon(RiffleIcons.Download, contentDescription = null) },
                selected = false,
                onClick = onDownloadsSelected,
                modifier = Modifier.testTag(TestTags.NAV_DRAWER_DOWNLOADS),
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(Res.string.ui_drawer_settings)) },
            icon = { Icon(RiffleIcons.Settings, contentDescription = null) },
            selected = false,
            onClick = onSettingsSelected,
            modifier = Modifier.testTag(TestTags.NAV_DRAWER_SETTINGS),
        )
        KoFiDrawerButton()
        if (appVersion != null) {
            val sha = appSha?.takeIf { it.isNotEmpty() }
            Text(
                text = stringResource(
                    Res.string.ui_app_version_footer,
                    appVersion,
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
}

@Composable
private fun DrawerHeader(
    activeServer: Source?,
    allServers: List<Source>,
    serverVersions: Map<String, String>,
    onServerSelected: (Source) -> Unit,
    isRiffleActive: Boolean = false,
    onRiffleSelected: () -> Unit = {},
) {
    val activeVersion = activeServer?.id?.let { serverVersions[it] }
    var switcherExpanded by remember { mutableStateOf(false) }
    var headerWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { headerWidth = with(density) { it.width.toDp() } },
    ) {
        ListItem(
            leadingContent = if (isRiffleActive) {
                { RiffleAppIcon(size = 24.dp) }
            } else {
                activeServer?.let { server -> { SourceRowIcon(server = server) } }
            },
            headlineContent = {
                if (isRiffleActive) {
                    AutoShrinkingSingleLineText(text = stringResource(Res.string.ui_drawer_riffle))
                } else {
                    val name = activeServer?.let { localizedSourceDisplayName(it) }
                        ?: stringResource(Res.string.ui_drawer_no_source)
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
                        AutoShrinkingSingleLineText(text = name)
                    }
                }
            },
            supportingContent = if (isRiffleActive) {
                null
            } else {
                {
                    val support = activeServer?.let {
                        localizedSourceSwitcherSubtitle(source = it, version = activeVersion)
                    }
                    if (support != null) {
                        AutoShrinkingSingleLineText(
                            text = support,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                Icon(
                    imageVector = if (switcherExpanded) RiffleIcons.KeyboardArrowUp else RiffleIcons.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.ui_toggle_source_switcher),
                )
            },
            modifier = Modifier
                .testTag(TestTags.NAV_DRAWER_SOURCE_HEADER)
                .clickable { switcherExpanded = !switcherExpanded },
        )
        DropdownMenu(
            expanded = switcherExpanded,
            onDismissRequest = { switcherExpanded = false },
            modifier = if (headerWidth != Dp.Unspecified) Modifier.width(headerWidth) else Modifier,
        ) {
            if (shouldShowRiffleSource(allServers.size)) {
                DropdownMenuItem(
                    text = { AutoShrinkingSingleLineText(text = stringResource(Res.string.ui_drawer_riffle)) },
                    leadingIcon = { RiffleAppIcon(size = 24.dp) },
                    trailingIcon = {
                        if (isRiffleActive) {
                            Icon(RiffleIcons.Check, contentDescription = stringResource(Res.string.ui_active_source))
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        switcherExpanded = false
                        onRiffleSelected()
                    },
                )
                HorizontalDivider()
            }
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
                                AutoShrinkingSingleLineText(text = displayName)
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
                        if (server.isActive && !isRiffleActive) {
                            Icon(RiffleIcons.Check, contentDescription = stringResource(Res.string.ui_active_source))
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

@Composable
private fun SourceRowIcon(server: Source) {
    if (server.type == SourceType.LOCAL_FILES) {
        Icon(
            imageVector = RiffleIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    } else {
        SourceIcon(source = server, size = 24.dp)
    }
}

internal fun buildSupportingLine(host: String?, version: String?): String? {
    val v = version?.let { "v$it" }
    return when {
        host != null && v != null -> "$host · $v"
        host != null -> host
        v != null -> v
        else -> null
    }
}

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
