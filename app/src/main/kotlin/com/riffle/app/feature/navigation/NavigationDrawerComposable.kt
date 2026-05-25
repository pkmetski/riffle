package com.riffle.app.feature.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.riffle.app.BuildConfig
import com.riffle.core.domain.Library
import com.riffle.core.domain.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiffleNavigationDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    activeServer: Server?,
    allServers: List<Server>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    serverVersion: String?,
    onDrawerOpened: () -> Unit,
    onServerSelected: (Server) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onDownloadsSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet {
                LaunchedEffect(drawerState.currentValue) {
                    if (drawerState.currentValue == DrawerValue.Open) {
                        onDrawerOpened()
                    }
                }
                Column(modifier = Modifier.fillMaxHeight()) {
                    DrawerHeader(
                        activeServer = activeServer,
                        allServers = allServers,
                        serverVersion = serverVersion,
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
        },
        content = content,
    )
}

@Composable
private fun DrawerHeader(
    activeServer: Server?,
    allServers: List<Server>,
    serverVersion: String?,
    onServerSelected: (Server) -> Unit,
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
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
                if (serverVersion != null) {
                    Text("$url · $serverVersion")
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
                            Text(
                                text = server.url.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = if (server.isActive) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        switcherExpanded = false
                        onServerSelected(server)
                    },
                )
            }
        }
    }
}
