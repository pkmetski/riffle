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
import com.riffle.core.domain.Library
import com.riffle.core.domain.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleNavigationDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    usePermanentDrawer: Boolean = false,
    activeServer: Server?,
    allServers: List<Server>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    serverVersions: Map<String, String>,
    onServerSelected: (Server) -> Unit,
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
                PermanentDrawerSheet(modifier = Modifier.width(280.dp)) { sheetBody() }
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
    activeServer: Server?,
    allServers: List<Server>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    serverVersions: Map<String, String>,
    onServerSelected: (Server) -> Unit,
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
        NavigationDrawerItem(
            label = { Text("Downloads") },
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            selected = false,
            onClick = onDownloadsSelected,
        )
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
        Text(
            text = "Riffle v${BuildConfig.VERSION_NAME}",
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
    activeServer: Server?,
    allServers: List<Server>,
    serverVersions: Map<String, String>,
    onServerSelected: (Server) -> Unit,
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
            headlineContent = {
                val name = activeServer?.displayName ?: "No server"
                val username = activeServer?.username?.takeIf { it.isNotEmpty() }
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
                val url = activeServer?.url?.value ?: ""
                if (activeVersion != null) {
                    Text("$url · $activeVersion")
                } else {
                    Text(url)
                }
            },
            trailingContent = {
                Icon(
                    imageVector = if (switcherExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle server switcher",
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
                            val username = server.username.takeIf { it.isNotEmpty() }
                            if (username != null) {
                                Text(
                                    buildAnnotatedString {
                                        append(server.displayName)
                                        append(" ")
                                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                            append("[$username]")
                                        }
                                    }
                                )
                            } else {
                                Text(server.displayName)
                            }
                            val version = serverVersions[server.id]
                            Text(
                                text = if (version != null) "${server.url.value} · $version" else server.url.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        if (server.isActive) {
                            Icon(Icons.Default.Check, contentDescription = null)
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
