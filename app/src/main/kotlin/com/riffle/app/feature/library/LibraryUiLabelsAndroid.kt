package com.riffle.app.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.feature.library.ui.AnnotationSearchLabels
import com.riffle.feature.library.ui.FilteredBooksLabels
import com.riffle.feature.library.ui.PlaylistLabels

/**
 * Android's `res/values*` catalogue for the shared playlist surfaces.
 *
 * Same arrangement as `chapterMapProgressLabelTemplates()` and `androidPlayerChromeLabels()`:
 * start from [PlaylistLabels.English] and `copy()` only the fields `res/values*` actually has, so
 * Bulgarian and Spanish keep working with no resource migration. `stringResource(id)` is called
 * with no format args so the raw `%1$d` template survives for the shared `formatTemplate`.
 */
@Composable
internal fun androidPlaylistLabels(): PlaylistLabels = PlaylistLabels.English.copy(
    back = stringResource(R.string.ui_back),
    play = stringResource(R.string.ui_play),
    loading = stringResource(R.string.ui_loading),
    playlistIsEmpty = stringResource(R.string.ui_this_playlist_is_empty),
    removeFromPlaylist = stringResource(R.string.ui_remove_from_playlist),
    noPlaylistsFromAnyItem = stringResource(R.string.ui_no_playlists_yet_create_one_from_any_item),
    addToPlaylist = stringResource(R.string.ui_add_to_playlist),
    newPlaylist = stringResource(R.string.ui_new_playlist),
    noPlaylistsGetStarted = stringResource(R.string.ui_no_playlists_yet_create_one_to_get_started),
    inThisPlaylist = stringResource(R.string.ui_in_this_playlist),
    name = stringResource(R.string.ui_name),
    create = stringResource(R.string.ui_create),
    cancel = stringResource(R.string.ui_cancel),
)

/** Android's `res/values*` catalogue for the shared facet drill-in. */
@Composable
internal fun androidFilteredBooksLabels(): FilteredBooksLabels = FilteredBooksLabels.English.copy(
    back = stringResource(R.string.ui_back),
    noBooksFound = stringResource(R.string.ui_no_books_found),
)

/**
 * Android's `res/values*` catalogue for the shared annotation-search surfaces.
 *
 * The two `%1$s` templates are fetched without format arguments so the raw template survives for
 * the shared `formatTemplate` to expand — Android's `stringResource(id, arg)` would expand it
 * here and the shared code would then have nothing to substitute.
 */
@Composable
internal fun androidAnnotationSearchLabels(): AnnotationSearchLabels = AnnotationSearchLabels.English.copy(
    back = stringResource(R.string.ui_back),
    titleTemplate = stringResource(R.string.ui_annotations_query_title),
    noResultsTemplate = stringResource(R.string.ui_no_annotations_for_query),
)
