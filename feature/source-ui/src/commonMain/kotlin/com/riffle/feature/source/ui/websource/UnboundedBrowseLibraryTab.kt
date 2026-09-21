package com.riffle.feature.source.ui.websource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.catalog.CatalogFacet
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_all
import com.riffle.feature.source.ui.generated.resources.ui_any
import com.riffle.feature.source.ui.generated.resources.ui_not_started
import com.riffle.feature.source.ui.generated.resources.ui_unowned
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Library tab of every unbounded web source: the filter-chip strip over
 * [UnboundedBrowseContent]. One implementation for Chitanka, Gutenberg, radio.es, O'Reilly and
 * both hosts — the three Android browse screens each carried a near-identical private
 * `LibraryTabContent`, which is exactly the drift class AGENTS.md's "no private platform copy of
 * a shared derivation" rule exists to stop.
 *
 * Per-source variation is expressed as data, not as a fork:
 *  * [plainFacets] picks which facets render as plain chips. Chitanka renders all of them;
 *    Gutenberg keeps `language:` facets out of the strip and puts them behind a dropdown;
 *    radio.es keeps both `language:` and country facets out.
 *  * [leadingFacetChips] / [trailingFacetChips] emit the source's dropdown chips before and after
 *    the plain ones, using [FacetDropdownChip].
 *
 * The whole facet block is gated on the *unfiltered* facet list being non-empty, exactly as all
 * three screens did, so a source whose facets are all behind dropdowns still gets its "All" chip.
 */
@Composable
fun UnboundedBrowseLibraryTab(
    viewModel: UnboundedBrowseViewModel,
    onCoverScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isAudio: Boolean = false,
    coverCellSizeMultiplier: Float = 1f,
    plainFacets: (List<CatalogFacet>) -> List<CatalogFacet> = { it },
    leadingFacetChips: (LazyListScope.(facets: List<CatalogFacet>, selectedFacet: String?, onSelectFacet: (String?) -> Unit) -> Unit)? = null,
    trailingFacetChips: (LazyListScope.(facets: List<CatalogFacet>, selectedFacet: String?, onSelectFacet: (String?) -> Unit) -> Unit)? = null,
) {
    val items by viewModel.filteredItems.collectAsState()
    val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()
    val unownedFilterActive by viewModel.unownedFilterActive.collectAsState()
    val hasServerSources by viewModel.hasServerSources.collectAsState()
    val facets by viewModel.facets.collectAsState()
    val selectedFacet by viewModel.selectedFacet.collectAsState()
    val query by viewModel.query.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isPaging by viewModel.isPaging.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CheckableFilterChip(
                    selected = notStartedFilterActive,
                    onClick = { viewModel.toggleNotStartedFilter() },
                    label = Res.string.ui_not_started,
                )
            }
            if (hasServerSources) {
                item {
                    CheckableFilterChip(
                        selected = unownedFilterActive,
                        onClick = { viewModel.toggleUnownedFilter() },
                        label = Res.string.ui_unowned,
                    )
                }
            }
            if (facets.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = selectedFacet == null,
                        onClick = { viewModel.selectFacet(null) },
                        label = { Text(stringResource(Res.string.ui_all)) },
                    )
                }
                leadingFacetChips?.invoke(this, facets, selectedFacet, viewModel::selectFacet)
                items(plainFacets(facets), key = { it.key }) { facet ->
                    FilterChip(
                        selected = selectedFacet == facet.key,
                        onClick = { viewModel.selectFacet(facet.key) },
                        label = { Text(facet.label) },
                    )
                }
                trailingFacetChips?.invoke(this, facets, selectedFacet, viewModel::selectFacet)
            }
        }
        UnboundedBrowseContent(
            isOffline = isOffline,
            isLoading = isLoading,
            error = error,
            items = items,
            query = query,
            isPaging = isPaging,
            hasMore = hasMore,
            onLoadMore = viewModel::loadMore,
            onCoverScaleChange = onCoverScaleChange,
            itemKey = { it.id },
            coverCellSizeMultiplier = coverCellSizeMultiplier,
        ) { item ->
            WebSourceCatalogItemCard(
                item = item,
                isAudio = isAudio,
                onClick = { viewModel.openDetail(item) },
            )
        }
    }
}

@Composable
private fun CheckableFilterChip(selected: Boolean, onClick: () -> Unit, label: StringResource) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(label)) },
        leadingIcon = if (selected) {
            {
                Icon(
                    RiffleIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

/**
 * A filter chip that opens a dropdown of [facets] instead of toggling one.
 *
 * Gutenberg's language list, radio.es's language list and radio.es's country list were three
 * byte-for-byte copies of this in `:app`; the only differences were the chip label and which
 * subset of the facets they were handed.
 *
 * [labelFor] receives the selected facet's label, or `null` when nothing in this group is
 * selected, and returns the chip's text (e.g. `"Language: Any"`).
 */
@Composable
fun FacetDropdownChip(
    facets: List<CatalogFacet>,
    selectedFacet: String?,
    onSelectFacet: (String?) -> Unit,
    labelFor: @Composable (selectedLabel: String?) -> String,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = facets.firstOrNull { it.key == selectedFacet }

    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(labelFor(selected?.label)) },
            trailingIcon = {
                Icon(
                    RiffleIcons.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.ui_any)) },
                onClick = {
                    onSelectFacet(null)
                    expanded = false
                },
                leadingIcon = if (selected == null) {
                    { Icon(RiffleIcons.Check, contentDescription = null) }
                } else {
                    null
                },
            )
            facets.forEach { facet ->
                DropdownMenuItem(
                    text = { Text(facet.label) },
                    onClick = {
                        onSelectFacet(facet.key)
                        expanded = false
                    },
                    leadingIcon = if (facet.key == selectedFacet) {
                        { Icon(RiffleIcons.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
