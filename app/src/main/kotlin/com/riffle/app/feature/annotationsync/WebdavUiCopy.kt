package com.riffle.app.feature.annotationsync

/**
 * User-facing WebDAV copy shared by Settings and the connection form.
 *
 * Audiobookshelf annotations sync through ABS bookmarks, while Komga is currently the only source
 * that uses WebDAV. The copy states that present scope without tying the generic WebDAV transport
 * permanently to Komga; future sources can join without renaming the controls.
 */
internal object WebdavUiCopy {
    const val SECTION_TITLE = "WebDAV annotation sync for Komga"
    const val SCREEN_TITLE = "WebDAV annotation sync for Komga"
    const val CONFIGURE_TITLE = "Configure WebDAV"
    const val ADD_TITLE = "Add WebDAV"
    const val EDIT_TITLE = "Edit WebDAV"
    const val HELP_TEXT =
        "Sync highlights, notes, and bookmarks between your devices via a WebDAV server. Available for Komga sources."
    const val NOT_CONFIGURED_STATUS =
        "Not configured · available for Komga sources"
}
