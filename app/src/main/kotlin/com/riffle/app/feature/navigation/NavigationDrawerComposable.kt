package com.riffle.app.feature.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                DrawerHeader(
                    activeServer = activeServer,
                    allServers = allServers,
                    onServerSelected = onServerSelected,
                )
                visibleLibraries.forEach { library ->
                    NavigationDrawerItem(
                        label = { Text(library.name) },
                        selected = library.id == activeLibraryId,
                        onClick = { onLibrarySelected(library) },
                    )
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
            }
        },
        content = content,
    )
}

@Composable
private fun DrawerHeader(
    activeServer: Server?,
    allServers: List<Server>,
    onServerSelected: (Server) -> Unit,
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(activeServer?.displayName ?: "No server") },
            supportingContent = { Text(activeServer?.url?.value ?: "") },
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
                        Text(server.displayName)
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
