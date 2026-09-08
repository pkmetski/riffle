package com.riffle.feature.source.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_add_source
import com.riffle.feature.source.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.generated.resources.ui_coming_soon
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

data class SourceTypeCard(
    val type: SourceType,
    val titleRes: StringResource,
    val subtitleRes: StringResource,
    val enabled: Boolean,
    val comingSoon: Boolean,
)

/**
 * Cards shown by [SourceTypePickerScreen]. Iterates every registered [WebSourceDescriptors]
 * entry and hides `descriptor.isSingleton` cards whose type is already in [installedTypes].
 * ADR 0053: adding a new source needs a descriptor object with `pickerOrder` + `pickerBlurb`
 * set; no edit required here.
 *
 * [developerModeEnabled] gates `descriptor.requiresDeveloperMode` cards (O'Reilly): they render
 * only when dev mode is unlocked. Add-time gate only — a source already installed while dev mode
 * was on is unaffected by this flag afterward. Default `false` so callers that don't thread the
 * flag never surface a dev-gated card by accident.
 */
internal fun sourceTypeCards(
    installedTypes: Set<SourceType> = emptySet(),
    developerModeEnabled: Boolean = false,
): List<SourceTypeCard> =
    WebSourceDescriptors.all
        .sortedBy { it.pickerOrder }
        .mapNotNull { descriptor ->
            if (descriptor.isSingleton && descriptor.type in installedTypes) return@mapNotNull null
            if (descriptor.requiresDeveloperMode && !developerModeEnabled) return@mapNotNull null
            SourceTypeCard(
                type = descriptor.type,
                titleRes = sourceDisplayNameRes(descriptor.type),
                subtitleRes = sourcePickerBlurbRes(descriptor.type),
                enabled = true,
                comingSoon = false,
            )
        }

private fun testTagFor(type: SourceType): String = "SourceTypeCard.${type.name}"

/**
 * The Add-Source picker, shared by Android (`:app` nav graph) and iOS (`:shared` HomeScreen).
 *
 * [isExpandedWidth] replaces the Android-only `WindowSizeClass` param — callers compute it
 * (`windowSizeClass.widthSizeClass == Expanded` on Android). [enabledTypes], when non-null,
 * restricts which cards are tappable; iOS passes the set of source types it can actually
 * install so the remaining cards render greyed-out rather than disappearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTypePickerScreen(
    isExpandedWidth: Boolean,
    onNavigateBack: () -> Unit,
    onPick: (SourceType) -> Unit,
    installedTypes: Set<SourceType> = emptySet(),
    developerModeEnabled: Boolean = false,
    enabledTypes: Set<SourceType>? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_add_source)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(SourceUiIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            isExpandedWidth = isExpandedWidth,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sourceTypeCards(
                    installedTypes = installedTypes,
                    developerModeEnabled = developerModeEnabled,
                ).forEach { card ->
                    val enabled = enabledTypes?.contains(card.type) ?: card.enabled
                    SourceTypeCardRow(
                        card = card.copy(enabled = enabled),
                        onClick = if (enabled) ({ onPick(card.type) }) else null,
                    )
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
                    imageVector = SourceUiIcons.Folder,
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
                Text(stringResource(card.titleRes), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(2.dp))
                Text(
                    stringResource(card.subtitleRes),
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
                        Text(stringResource(Res.string.ui_coming_soon), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
