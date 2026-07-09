package com.riffle.app.feature.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.app.ui.TabletContentWidthContainer

sealed interface SourceTypeChoice {
    data object Audiobookshelf : SourceTypeChoice
    data object LocalFiles : SourceTypeChoice
}

data class SourceTypeCard(
    val type: SourceTypeChoice,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val comingSoon: Boolean,
)

internal fun sourceTypeCards(): List<SourceTypeCard> = listOf(
    SourceTypeCard(
        type = SourceTypeChoice.Audiobookshelf,
        title = "Audiobookshelf",
        subtitle = "Stream ebooks and audiobooks from your Audiobookshelf server.",
        enabled = true,
        comingSoon = false,
    ),
    SourceTypeCard(
        type = SourceTypeChoice.LocalFiles,
        title = "Local files",
        subtitle = "Read EPUBs and PDFs from a folder on this device.",
        enabled = false,
        comingSoon = true,
    ),
)

private fun iconFor(type: SourceTypeChoice): ImageVector = when (type) {
    SourceTypeChoice.Audiobookshelf -> Icons.Default.Cloud
    SourceTypeChoice.LocalFiles -> Icons.Default.Folder
}

private fun testTagFor(type: SourceTypeChoice): String = when (type) {
    SourceTypeChoice.Audiobookshelf -> "SourceTypeCard.Audiobookshelf"
    SourceTypeChoice.LocalFiles -> "SourceTypeCard.LocalFiles"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTypePickerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onPickAudiobookshelf: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add source") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sourceTypeCards().forEach { card ->
                    SourceTypeCardRow(
                        card = card,
                        onClick = when (card.type) {
                            SourceTypeChoice.Audiobookshelf -> onPickAudiobookshelf
                            SourceTypeChoice.LocalFiles -> null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceTypeCardRow(card: SourceTypeCard, onClick: (() -> Unit)?) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .testTag(testTagFor(card.type))
    val cardModifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val contentAlpha = if (card.enabled) 1f else 0.5f
            Icon(
                imageVector = iconFor(card.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp).alpha(contentAlpha),
            )
            Column(
                modifier = Modifier.weight(1f).alpha(contentAlpha),
            ) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(2.dp))
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.comingSoon) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Coming soon", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
