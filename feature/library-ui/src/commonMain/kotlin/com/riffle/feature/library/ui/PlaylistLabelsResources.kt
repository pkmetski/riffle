package com.riffle.feature.library.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_add_to_playlist
import com.riffle.feature.library.ui.generated.resources.ui_annotations_query_title
import com.riffle.feature.library.ui.generated.resources.ui_audiobook_bookmark_fallback_title
import com.riffle.feature.library.ui.generated.resources.ui_back
import com.riffle.feature.library.ui.generated.resources.ui_bookmark_fallback_title
import com.riffle.feature.library.ui.generated.resources.ui_cancel
import com.riffle.feature.library.ui.generated.resources.ui_create
import com.riffle.feature.library.ui.generated.resources.ui_in_this_playlist
import com.riffle.feature.library.ui.generated.resources.ui_loading
import com.riffle.feature.library.ui.generated.resources.ui_name
import com.riffle.feature.library.ui.generated.resources.ui_new_playlist
import com.riffle.feature.library.ui.generated.resources.ui_no_annotations_for_query
import com.riffle.feature.library.ui.generated.resources.ui_no_books_found
import com.riffle.feature.library.ui.generated.resources.ui_no_playlists_yet_create_one_from_any_item
import com.riffle.feature.library.ui.generated.resources.ui_no_playlists_yet_create_one_to_get_started
import com.riffle.feature.library.ui.generated.resources.ui_play
import com.riffle.feature.library.ui.generated.resources.ui_playlist_count_one
import com.riffle.feature.library.ui.generated.resources.ui_playlist_count_other
import com.riffle.feature.library.ui.generated.resources.ui_remove_from_playlist
import com.riffle.feature.library.ui.generated.resources.ui_this_playlist_is_empty
import org.jetbrains.compose.resources.stringResource

/** Builds [PlaylistLabels] from composeResources, picking up the active locale automatically. */
@Composable
fun playlistLabels(): PlaylistLabels = PlaylistLabels(
    back = stringResource(Res.string.ui_back),
    play = stringResource(Res.string.ui_play),
    loading = stringResource(Res.string.ui_loading),
    playlistIsEmpty = stringResource(Res.string.ui_this_playlist_is_empty),
    removeFromPlaylist = stringResource(Res.string.ui_remove_from_playlist),
    noPlaylistsFromAnyItem = stringResource(Res.string.ui_no_playlists_yet_create_one_from_any_item),
    addToPlaylist = stringResource(Res.string.ui_add_to_playlist),
    newPlaylist = stringResource(Res.string.ui_new_playlist),
    noPlaylistsGetStarted = stringResource(Res.string.ui_no_playlists_yet_create_one_to_get_started),
    inThisPlaylist = stringResource(Res.string.ui_in_this_playlist),
    name = stringResource(Res.string.ui_name),
    create = stringResource(Res.string.ui_create),
    cancel = stringResource(Res.string.ui_cancel),
    itemCountOne = stringResource(Res.string.ui_playlist_count_one),
    itemCountOther = stringResource(Res.string.ui_playlist_count_other),
)

/** Builds [FilteredBooksLabels] from composeResources. */
@Composable
fun filteredBooksLabels(): FilteredBooksLabels = FilteredBooksLabels(
    back = stringResource(Res.string.ui_back),
    noBooksFound = stringResource(Res.string.ui_no_books_found),
)

/** Builds [AnnotationSearchLabels] from composeResources. */
@Composable
fun annotationSearchLabels(): AnnotationSearchLabels = AnnotationSearchLabels(
    back = stringResource(Res.string.ui_back),
    titleTemplate = stringResource(Res.string.ui_annotations_query_title),
    noResultsTemplate = stringResource(Res.string.ui_no_annotations_for_query),
    bookmarkFallbackTitle = stringResource(Res.string.ui_bookmark_fallback_title),
    audiobookBookmarkFallbackTitle = stringResource(Res.string.ui_audiobook_bookmark_fallback_title),
)
