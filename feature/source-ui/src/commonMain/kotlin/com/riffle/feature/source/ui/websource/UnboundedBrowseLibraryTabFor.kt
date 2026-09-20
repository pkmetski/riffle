package com.riffle.feature.source.ui.websource

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_any
import com.riffle.feature.source.ui.generated.resources.ui_country_filter
import com.riffle.feature.source.ui.generated.resources.ui_language_filter
import org.jetbrains.compose.resources.stringResource

/**
 * Renders [UnboundedBrowseLibraryTab] with [sourceType]'s chip strip.
 *
 * This is the one place that says "Gutenberg hides its languages behind a dropdown, radio.es
 * hides languages *and* countries, Chitanka shows every facet as a chip". Both hosts call it, so
 * the iOS browse screen gets the same strip Android has instead of re-deriving the groups.
 *
 * [isAudio] comes from the root the user is browsing (Chitanka's gramofonche root, radio.es
 * always) and drives both the square cover art and the roomier cell size, exactly as each
 * Android screen computed inline.
 */
@Composable
fun UnboundedBrowseLibraryTabFor(
    sourceType: SourceType,
    viewModel: UnboundedBrowseViewModel,
    onCoverScaleChange: (Float) -> Unit,
    isAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    UnboundedBrowseLibraryTab(
        viewModel = viewModel,
        onCoverScaleChange = onCoverScaleChange,
        modifier = modifier,
        isAudio = isAudio,
        coverCellSizeMultiplier = if (isAudio) 4f / 3f else 1f,
        plainFacets = when (sourceType) {
            SourceType.GUTENBERG -> ::gutenbergTopicFacets
            SourceType.RADIO_ES -> ::radioEsCategoryFacets
            else -> ({ it })
        },
        leadingFacetChips = if (sourceType == SourceType.GUTENBERG) {
            { facets, selectedFacet, onSelectFacet ->
                val languages = gutenbergLanguageFacets(facets)
                if (languages.isNotEmpty()) {
                    item {
                        FacetDropdownChip(
                            facets = languages,
                            selectedFacet = selectedFacet,
                            onSelectFacet = onSelectFacet,
                            labelFor = { selected ->
                                stringResource(Res.string.ui_language_filter, selected ?: stringResource(Res.string.ui_any))
                            },
                        )
                    }
                }
            }
        } else {
            null
        },
        trailingFacetChips = if (sourceType == SourceType.RADIO_ES) {
            { facets, selectedFacet, onSelectFacet ->
                val languages = radioEsLanguageFacets(facets)
                if (languages.isNotEmpty()) {
                    item {
                        FacetDropdownChip(
                            facets = languages,
                            selectedFacet = selectedFacet,
                            onSelectFacet = onSelectFacet,
                            labelFor = { selected ->
                                stringResource(Res.string.ui_language_filter, selected ?: stringResource(Res.string.ui_any))
                            },
                        )
                    }
                }
                val countries = radioEsCountryFacets(facets)
                if (countries.isNotEmpty()) {
                    item {
                        FacetDropdownChip(
                            facets = countries,
                            selectedFacet = selectedFacet,
                            onSelectFacet = onSelectFacet,
                            labelFor = { selected ->
                                stringResource(Res.string.ui_country_filter, selected ?: stringResource(Res.string.ui_any))
                            },
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}
