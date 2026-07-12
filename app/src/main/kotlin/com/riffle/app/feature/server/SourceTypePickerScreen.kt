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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.ui.source.SourceTypeIcon
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.WebSourceDescriptors

data class SourceTypeCard(
    val type: SourceType,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val comingSoon: Boolean,
)

/**
 * Cards shown by [SourceTypePickerScreen]. Iterates every registered [WebSourceDescriptors]
 * entry and hides `descriptor.isSingleton` cards whose type is already in [installedTypes].
 * ADR 0044: adding a new source needs a descriptor object; no edit required here.
 *
 * The card's `subtitle` is authored per source (registered as [pickerBlurbFor]) because the
 * picker blurb is longer and more marketing-shaped than the drawer's static subtitle.
 */
internal fun sourceTypeCards(
    installedTypes: Set<SourceType> = emptySet(),
): List<SourceTypeCard> =
    WebSourceDescriptors.all
        .sortedBy { pickerOrderOf(it.type) }
        .mapNotNull { descriptor ->
            if (descriptor.isSingleton && descriptor.type in installedTypes) return@mapNotNull null
            SourceTypeCard(
                type = descriptor.type,
                title = descriptor.displayName,
                subtitle = pickerBlurbFor(descriptor.type),
                enabled = true,
                comingSoon = false,
            )
        }

// Order matches the pre-refactor UI ordering: ABS, LocalFiles, Chitanka, Gutenberg, then any
// future source in registration order. Kept as a data-driven table so a new source can pin its
// slot by adding one entry (falling back to Int.MAX_VALUE places new sources at the end).
private fun pickerOrderOf(type: SourceType): Int = when (type) {
    SourceType.ABS -> 0
    SourceType.LOCAL_FILES -> 1
    SourceType.CHITANKA -> 2
    SourceType.GUTENBERG -> 3
}

private fun pickerBlurbFor(type: SourceType): String = when (type) {
    SourceType.ABS -> "Stream ebooks and audiobooks from your Audiobookshelf server."
    SourceType.LOCAL_FILES -> "Read EPUBs and PDFs from a folder on this device."
    SourceType.CHITANKA -> "Browse Bulgarian ebooks (chitanka.info) and audiobooks (gramofonche)."
    SourceType.GUTENBERG -> "Browse tens of thousands of free public-domain ebooks."
}

private fun testTagFor(type: SourceType): String = "SourceTypeCard.${type.name}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTypePickerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onPick: (SourceType) -> Unit,
    installedTypes: Set<SourceType> = emptySet(),
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
                sourceTypeCards(installedTypes = installedTypes).forEach { card ->
                    SourceTypeCardRow(card = card, onClick = { onPick(card.type) })
                }
            }
        }
    }
}

@Composable
private fun SourceTypeCardRow(card: SourceTypeCard, onClick: (() -> Unit)?) {
    // Merge descendants so the whole card presents as one semantic node — TalkBack reads
    // title+subtitle+"Coming soon" together, and Compose UI tests can find the click action
    // via `onNodeWithText(card.title)` (not only via the test tag).
    val baseModifier = Modifier
        .fillMaxWidth()
        .testTag(testTagFor(card.type))
        .semantics(mergeDescendants = true) {
            if (!card.enabled) disabled()
        }
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
            val iconModifier = Modifier.size(40.dp).alpha(contentAlpha)
            when (card.type) {
                // LocalFiles intentionally keeps its Material Folder icon — see
                // SourceIconResolver: LOCAL_FILES has no monogram drawable.
                SourceType.LOCAL_FILES -> Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = iconModifier,
                )
                SourceType.ABS -> SourceTypeIcon(
                    type = SourceType.ABS,
                    serverType = ServerType.AUDIOBOOKSHELF,
                    modifier = iconModifier,
                    size = 40.dp,
                )
                else -> SourceTypeIcon(
                    type = card.type,
                    modifier = iconModifier,
                    size = 40.dp,
                )
            }
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
