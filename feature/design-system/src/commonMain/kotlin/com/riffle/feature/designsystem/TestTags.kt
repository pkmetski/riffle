package com.riffle.feature.designsystem

object TestTags {

    // ── Navigation / top-level ────────────────────────────────────────────────
    const val NAV_BACK = "nav_back"
    const val NAV_DRAWER_TOGGLE = "nav_drawer_toggle"
    const val NAV_TAB_IN_PROGRESS = "nav_tab_in_progress"
    const val NAV_TAB_TO_READ = "nav_tab_to_read"
    const val NAV_TAB_ANNOTATIONS = "nav_tab_annotations"

    // ── Library ───────────────────────────────────────────────────────────────
    const val LIBRARY_SEARCH = "library_search"
    const val LIBRARY_FILTER = "library_filter"
    const val LIBRARY_SORT = "library_sort"
    const val LIBRARY_OVERFLOW_MENU = "library_overflow_menu"
    fun libraryBookItem(index: Int) = "library_book_item_$index"

    // ── Book detail ───────────────────────────────────────────────────────────
    const val BOOK_DETAIL_BACK = "book_detail_back"
    const val BOOK_DETAIL_OPEN = "book_detail_open"
    const val BOOK_DETAIL_DOWNLOAD = "book_detail_download"
    const val BOOK_DETAIL_MARK_READ = "book_detail_mark_read"
    const val BOOK_DETAIL_OVERFLOW = "book_detail_overflow"

    // ── EPUB reader (Android) ─────────────────────────────────────────────────
    const val READER_BACK = "reader_back"
    const val READER_TOC = "reader_toc"
    const val READER_ANNOTATIONS = "reader_annotations"
    const val READER_SEARCH = "reader_search"
    const val READER_SEARCH_FIELD = "search_field"
    const val READER_SEARCH_COUNT = "search_result_count"
    const val READER_SEARCH_PREV = "search_prev"
    const val READER_SEARCH_NEXT = "search_next"
    const val READER_SEARCH_CLOSE = "reader_search_close"
    const val READER_SETTINGS = "reader_settings"
    const val READER_BOOKMARK = "reader_bookmark"
    const val READER_READALOUD = "reader_readaloud"
    const val READER_MORE_OPTIONS = "reader_more_options"
    const val READER_LOADING = "reader_loading"
    const val READER_READY = "reader_ready"
    const val READER_ERROR_STATE = "reader_error_state"
    const val READER_TOC_PANEL = "toc_panel"
    const val READER_NAV_RAIL = "chapter_navigation_rail"
    const val READER_AUTOSCROLL = "reader_autoscroll"

    // ── Readaloud player ──────────────────────────────────────────────────────
    const val READALOUD_MINI_PLAYER = "readaloud_mini_player"
    const val READALOUD_SPEED = "readaloud_speed"
    const val READALOUD_REWIND = "readaloud_rewind"
    const val READALOUD_PREV_CHAPTER = "readaloud_prev_chapter"
    const val READALOUD_PLAY_PAUSE = "readaloud_play_pause"
    const val READALOUD_NEXT_CHAPTER = "readaloud_next_chapter"
    const val READALOUD_FORWARD = "readaloud_forward"
    const val READALOUD_CLOSE = "readaloud_close"
    const val READALOUD_OFFLINE_MESSAGE = "readaloud_offline_message"
    const val READALOUD_DOWNLOADING = "readaloud_downloading"
    const val READALOUD_DOWNLOAD_DIALOG = "readaloud_download_dialog"
    const val READALOUD_WIFI_ONLY = "readaloud_wifi_only"

    // ── PDF reader ────────────────────────────────────────────────────────────
    const val PDF_READER_BACK = "pdf_reader_back"
    const val PDF_READER_SEARCH = "pdf_reader_search"
    const val PDF_READER_TOC = "pdf_reader_toc"
    const val PDF_READER_SETTINGS = "pdf_reader_settings"
    const val PDF_READER_PAGE_INDICATOR = "pdf_reader_page_indicator"
    const val PDF_READER_FORMAT_TOGGLE = "pdf_reader_format_toggle"
    const val PDF_READER_ANNOTATIONS = "pdf_reader_annotations"

    // ── CBZ / comic reader ────────────────────────────────────────────────────
    const val CBZ_READER_BACK = "cbz_reader_back"
    const val CBZ_READER_SETTINGS = "cbz_reader_settings"
    const val CBZ_PAGER = "cbz_pager"
    const val CBZ_PANEL_VIEWER = "cbz_panel_viewer"
    const val CBZ_PANEL_PEEK = "cbz_panel_peek"
    const val CBZ_PANEL_PEEK_SKIP = "cbz_panel_peek_skip"
    const val CBZ_THUMBNAIL_STRIP = "cbz_thumbnail_strip"
    fun cbzThumb(pageIndex: Int) = "cbz_thumb_$pageIndex"

    // ── iOS EPUB reader (IosEpubReaderScreen) ─────────────────────────────────
    const val IOS_READER_BACK = "ios_reader_back"
    const val IOS_READER_ANNOTATIONS = "ios_reader_annotations"
    const val IOS_READER_TOC = "ios_reader_toc"
    const val IOS_READER_SEARCH = "ios_reader_search"
    const val IOS_READER_SEARCH_FIELD = "ios_reader_search_field"
    const val IOS_READER_AUTOSCROLL = "ios_reader_autoscroll"
    const val IOS_READER_CADENCE = "ios_reader_cadence"
    const val IOS_READER_BOOKMARK = "ios_reader_bookmark"
    const val IOS_READER_AUTOSCROLL_PAUSE = "ios_reader_autoscroll_pause"

    // These match the existing pre-tagged string values in AutoScrollUi / CadenceUi.
    const val READER_AUTOSCROLL_SLOWER = "auto_scroll_slower"
    const val READER_AUTOSCROLL_FASTER = "auto_scroll_faster"
    const val READER_CADENCE_SLOWER = "cadence_slower"
    const val READER_CADENCE_FASTER = "cadence_faster"
    const val IOS_READER_CADENCE_PAUSE = "ios_reader_cadence_pause"

    // ── iOS PDF reader ────────────────────────────────────────────────────────
    const val IOS_PDF_READER_BACK = "ios_pdf_reader_back"

    // ── iOS CBZ reader ────────────────────────────────────────────────────────
    const val IOS_CBZ_READER_BACK = "ios_cbz_reader_back"

    // ── Audiobook player ──────────────────────────────────────────────────────
    // String values for existing tags match what tests already reference.
    const val PLAYER_BACK = "player_back"
    const val PLAYER_BOOKMARK_RIBBON = "player_bookmark_ribbon"
    const val PLAYER_CHAPTERS_PILL = "player_chapters_pill"
    const val PLAYER_BOOKMARKS_PILL = "player_bookmarks_pill"
    const val PLAYER_SCRUBBER = "player_scrubber"
    const val PLAYER_COVER = "player_cover"
    const val PLAYER_FACTS = "player_facts"
    const val PLAYER_ELAPSED = "player_elapsed"
    const val PLAYER_REMAINING = "player_remaining"
    const val PLAYER_FAILED = "player_failed"
    const val PLAYER_PREV_CHAPTER = "player_prev_chapter"
    const val PLAYER_NEXT_CHAPTER = "player_next_chapter"
    const val PLAYER_PLAY_PAUSE = "player_play_pause"

    // Preserve existing kebab-case strings so XCUITests keep passing.
    const val AUDIOBOOK_REWIND = "audiobook-rewind"
    const val AUDIOBOOK_FORWARD = "audiobook-forward"
    const val AUDIOBOOK_SPEED_PILL = "audiobook_speed_pill"
    const val AUDIOBOOK_SLEEP_PILL = "audiobook_sleep_pill"
    const val BOOKMARK_DIALOG = "bookmark_dialog"
    const val BOOKMARK_TITLE_FIELD = "bookmark_title_field"
    const val BOOKMARK_SAVE = "bookmark_save"
    fun playerSpeedMinus(prefix: String) = "${prefix}_speed_minus"
    fun playerSpeedDisplay(prefix: String) = "${prefix}_speed_display"
    fun playerSpeedPlus(prefix: String) = "${prefix}_speed_plus"
    fun playerSpeedPreset(prefix: String, label: String) = "${prefix}_speed_preset_$label"
    fun sleepPreset(minutes: Int) = "sleep_preset_$minutes"
    const val SLEEP_TIMER_SHEET = "sleep_timer_sheet"
    const val SLEEP_END_OF_CHAPTER = "sleep_end_of_chapter"
    const val PLAYER_LIST_SHEET_TITLE = "player_list_sheet_title"

    // ── Annotations list ──────────────────────────────────────────────────────
    const val ANNOTATIONS_BACK = "annotations_back"
    const val ANNOTATIONS_SEARCH_FIELD = "annotation-search-field"
    fun annotationsItem(index: Int) = "annotations_item_$index"

    // ── Downloads ─────────────────────────────────────────────────────────────
    const val DOWNLOADS_BACK = "downloads_back"
    const val DOWNLOADS_REMOVE_ALL = "DownloadsScreen.RemoveAllDownloads"
    const val DOWNLOADS_CLEAR_CACHED = "DownloadsScreen.ClearAllCached"
    const val DOWNLOADS_CONFIRM_REMOVE_ALL = "DownloadsScreen.ConfirmRemoveAllDownloads"
    const val DOWNLOADS_CONFIRM_CLEAR_CACHED = "DownloadsScreen.ConfirmClearAllCached"

    // ── Settings ──────────────────────────────────────────────────────────────
    const val SETTINGS_BACK = "settings_back"

    // ── Source management ─────────────────────────────────────────────────────
    const val SOURCE_TYPE_PICKER_CONFIRM = "source_type_picker_confirm"
    const val SOURCE_TYPE_PICKER_CANCEL = "source_type_picker_cancel"
    fun sourceTypeItem(type: String) = "source_type_item_$type"
    const val ADD_LOCAL_FILES_DONE = "AddLocalFiles.Done"
    const val ADD_LOCAL_FILES_PICK_AGAIN = "AddLocalFiles.PickAgain"
    const val SELECT_LIBRARIES_BACK = "select_libraries_back"
    const val SELECT_LIBRARIES_CONTINUE = "select_libraries_continue"
    const val SELECT_LIBRARIES_GO_BACK = "select_libraries_go_back"
    fun selectLibraryToggle(libraryId: String) = "select_library_toggle_$libraryId"

    // ── Snackbar / global UI ─────────────────────────────────────────────────
    const val SNACKBAR_HOST = "RiffleSnackbarHost"

    // ── Library / playlist ────────────────────────────────────────────────────
    const val ANNOTATION_SEARCH_SUBMIT = "annotation-search-submit"
    const val ANNOTATION_SEARCH_RESULTS = "annotation-search-results"
    const val DETAIL_ADD_TO_PLAYLIST = "detail-add-to-playlist"
    const val FILTERED_BOOKS_GRID = "filtered-books-grid"
    const val PLAYLIST_NEW = "playlist-new"
    const val PLAYLIST_NAME_FIELD = "playlist-name-field"
    const val PLAYLIST_PLAY = "playlist-play"
    fun playlistRow(id: Any) = "playlist-row-$id"
    fun playlistPick(id: Any) = "playlist-pick-$id"
    fun playlistRemove(id: Any) = "playlist-remove-$id"
    fun playlistItem(id: Any) = "playlist-item-$id"
    fun annotationResult(id: Any) = "annotation-result-$id"
    fun audiobookBookmarkResult(id: Any) = "audiobook-bookmark-result-$id"
    fun facet(value: Any) = "facet-$value"

    // ── Settings (shared) ─────────────────────────────────────────────────────
    fun settingsTrailing(trailing: Any) = "settings-trailing-$trailing"
    fun settingsPanelToggle(label: Any) = "panel-toggle-$label"

    // ── Book download controls ────────────────────────────────────────────────
    const val BOOK_DOWNLOAD_CONTROLS = "BookDownloadControls"
    const val BOOK_DOWNLOAD_CONTROLS_EBOOK = "BookDownloadControls.Ebook"
    const val BOOK_DOWNLOAD_CONTROLS_AUDIOBOOK = "BookDownloadControls.Audiobook"
    const val BOOK_DOWNLOAD_CONTROLS_READALOUD = "BookDownloadControls.Readaloud"

    // ── Reader (additional) ───────────────────────────────────────────────────
    const val READER_NAV_COVER = "reader_nav_cover"

    // ── Annotation actions sheet ──────────────────────────────────────────────
    const val ANNOTATION_SWATCH_NONE = "annotation_swatch_none"
    fun annotationSwatch(colorToken: Any) = "annotation_swatch_$colorToken"
    fun annotationChip(styleToken: Any) = "annotation_chip_$styleToken"
    const val ANNOTATION_DELETE = "annotation_delete"
    const val ANNOTATION_NOTE_ROW = "annotation_note_row"
    const val NOTE_EDITOR_FIELD = "note_editor_field"
    const val NOTE_EDITOR_REMOVE = "note_editor_remove"
    const val NOTE_EDITOR_CANCEL = "note_editor_cancel"
    const val NOTE_EDITOR_SAVE = "note_editor_save"

    // ── Auto-scroll UI ────────────────────────────────────────────────────────
    const val AUTO_SCROLL_TOGGLE = "auto_scroll_toggle"
    const val AUTO_SCROLL_HUD_PILL = "auto_scroll_hud_pill"

    // ── Cadence UI ────────────────────────────────────────────────────────────
    const val CADENCE_TOGGLE = "cadence_toggle"
    const val CADENCE_HUD_PILL = "cadence_hud_pill"

    // ── Reader settings ───────────────────────────────────────────────────────
    const val READER_SETTINGS_COLORED_CHAPTER_MAP = "colored_chapter_map_toggle"

    // Formatting section — sliders (outer row tag + decrement/increment buttons)
    const val READER_SETTINGS_FONT_SIZE = "reader_settings_font_size"
    const val READER_SETTINGS_FONT_SIZE_DEC = "reader_settings_font_size_dec"
    const val READER_SETTINGS_FONT_SIZE_INC = "reader_settings_font_size_inc"
    const val READER_SETTINGS_LINE_SPACING = "reader_settings_line_spacing"
    const val READER_SETTINGS_LINE_SPACING_DEC = "reader_settings_line_spacing_dec"
    const val READER_SETTINGS_LINE_SPACING_INC = "reader_settings_line_spacing_inc"
    const val READER_SETTINGS_MARGINS = "reader_settings_margins"
    const val READER_SETTINGS_MARGINS_DEC = "reader_settings_margins_dec"
    const val READER_SETTINGS_MARGINS_INC = "reader_settings_margins_inc"
    const val READER_SETTINGS_JUSTIFY_TEXT = "reader_settings_justify_text"
    fun readerSettingsFont(family: String) = "reader_settings_font_$family"

    // Display section
    fun readerSettingsTheme(theme: String) = "reader_settings_theme_$theme"
    fun readerSettingsOrientation(orientation: String) = "reader_settings_orientation_$orientation"
    const val READER_SETTINGS_FORCE_PAGINATED_LANDSCAPE = "reader_settings_force_paginated_landscape"
    const val READER_SETTINGS_DOUBLE_PAGE = "reader_settings_double_page"

    // Behavior section
    const val READER_SETTINGS_KEEP_SCREEN_ON = "reader_settings_keep_screen_on"
    const val READER_SETTINGS_VOLUME_KEY_NAV = "reader_settings_volume_key_nav"
    const val READER_SETTINGS_INVERT_VOLUME_KEYS = "reader_settings_invert_volume_keys"

    // ── Chapter map overlay ───────────────────────────────────────────────────
    const val READING_PROGRESS_LABELS = "reading_progress_labels"
    const val READING_PROGRESS_CHAPTER = "reading_progress_chapter"
    const val READING_PROGRESS_CHAPTER_TIME = "reading_progress_chapter_time"
    const val READING_PROGRESS_CHAPTER_NAME = "reading_progress_chapter_name"
    const val READING_PROGRESS_PERCENT = "reading_progress_percent"
    const val READING_PROGRESS_BOOK_TIME = "reading_progress_book_time"

    // ── Sources section (settings) ────────────────────────────────────────────
    const val SOURCES_ADD_SOURCE = "SourcesSection.AddSource"
    const val SOURCE_SETTINGS_READALOUD_MATCHES = "SourceSettingsExpansion.ReadaloudMatches"
    const val LOCAL_FILES_ADD_FOLDER = "LocalFilesSourceRow.AddFolder"
    fun localFilesFolder(treeUri: Any) = "LocalFilesFolder.$treeUri"
    fun localFilesFolderRemove(treeUri: Any) = "LocalFilesFolder.Remove.$treeUri"
    const val LOCAL_FILES_CONFIRM_REMOVE_FOLDER = "LocalFilesSourceRow.ConfirmRemoveFolder"
    fun reorderableLibraryUp(libraryId: Any) = "ReorderableLibrary.Up.$libraryId"
    fun reorderableLibraryDown(libraryId: Any) = "ReorderableLibrary.Down.$libraryId"
}
