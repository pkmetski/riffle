package com.riffle.feature.library.ui

import com.riffle.feature.reader.ui.formatTemplate

/**
 * Every user-visible string the playlist surfaces draw, supplied by the host.
 *
 * Same arrangement as [com.riffle.feature.player.ui.PlayerChromeLabels] and
 * `com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates`, and for the same reason:
 * `:app` fills this from its `res/values*` `stringResource`s (so Bulgarian and Spanish keep
 * working with no resource migration and no APK asset bridging), and `:shared` fills it from
 * [English] because the iOS app has no i18n mechanism wired up yet (#1072 §5). Only the *strings*
 * differ per host — every derivation lives in this module, so the two platforms cannot drift on
 * how a playlist reads.
 *
 * Fields carrying `%1$d` are Android positional templates; expand them with [formatTemplate],
 * never with `String.format` (JVM-only).
 */
data class PlaylistLabels(
    /** Back-arrow content description. */
    val back: String,
    val play: String,
    val loading: String,
    /** Empty state of an existing playlist's detail screen. */
    val playlistIsEmpty: String,
    /** Content description of the per-row remove button. */
    val removeFromPlaylist: String,
    /** Empty state of the Playlists tab. */
    val noPlaylistsFromAnyItem: String,
    /** Title of the add-to-playlist sheet. */
    val addToPlaylist: String,
    val newPlaylist: String,
    /** Empty state inside the add-to-playlist sheet. */
    val noPlaylistsGetStarted: String,
    /** Content description of the tick beside a playlist the item is already in. */
    val inThisPlaylist: String,
    /** Label of the new-playlist name field. */
    val name: String,
    val create: String,
    val cancel: String,
    /** `"1 item"` */
    val itemCountOne: String,
    /** `"%1$d items"` */
    val itemCountOther: String,
) {
    companion object {
        /**
         * The English catalogue, verbatim from `app/src/main/res/values/strings.xml` (and, where
         * Android still inlines a literal, from the literal). Used by the iOS host, which has no
         * string-resource mechanism yet. Keep the two in step: these are the same keys, not a
         * second wording.
         */
        val English = PlaylistLabels(
            back = "Back",
            play = "Play",
            loading = "Loading…",
            playlistIsEmpty = "This playlist is empty.",
            removeFromPlaylist = "Remove from playlist",
            noPlaylistsFromAnyItem = "No playlists yet. Create one from any item.",
            addToPlaylist = "Add to playlist",
            newPlaylist = "New playlist",
            noPlaylistsGetStarted = "No playlists yet. Create one to get started.",
            inThisPlaylist = "In this playlist",
            name = "Name",
            create = "Create",
            cancel = "Cancel",
            itemCountOne = "1 item",
            itemCountOther = "%1\$d items",
        )
    }
}

/**
 * `"1 item"` / `"7 items"` — the count line under a playlist's name.
 *
 * One implementation for both hosts and for both places the line appears (the Playlists tab row
 * and the add-to-playlist picker row); Android's two call sites used to share a `private fun` in
 * `:app` that iOS had no access to at all.
 */
fun playlistItemCountLabel(count: Int, labels: PlaylistLabels): String =
    if (count == 1) labels.itemCountOne else formatTemplate(labels.itemCountOther, count)
