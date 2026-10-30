// Swift mirror of TestTags.kt (feature/design-system/src/commonMain/kotlin/.../TestTags.kt).
// Keep values in sync with that file — they are the single source of truth.
// Use these constants in XCUITest selectors instead of raw string literals.
enum RiffleTestTags {
    // Navigation
    static let navBack = "nav_back"
    static let navDrawerToggle = "nav_drawer_toggle"
    static let navDrawerSourceHeader = "nav_drawer_source_header"
    static let navDrawerDownloads = "nav_drawer_downloads"
    static let navDrawerSettings = "nav_drawer_settings"
    static let navDrawerRiffle = "nav_drawer_riffle"

    // Library browsing
    static let librarySectionBack = "library_section_back"
    static let librarySearch = "library_search"
    static let libraryFilter = "library_filter"
    static let librarySort = "library_sort"
    static let seriesDetailBack = "series_detail_back"
    static let collectionDetailBack = "collection_detail_back"

    // Book detail
    static let bookDetailBack = "book_detail_back"
    static let bookDetailOpen = "book_detail_open"
    static let bookDetailMarkRead = "book_detail_mark_read"
    static let bookDetailProgress = "book_detail_progress"

    // EPUB reader (iOS-native)
    static let iosReaderBack = "ios_reader_back"
    static let iosReaderAnnotations = "ios_reader_annotations"
    static let iosReaderToc = "ios_reader_toc"
    static let iosReaderSearch = "ios_reader_search"
    static let iosReaderSearchField = "ios_reader_search_field"
    static let iosReaderBookmark = "reader_bookmark"
    static let iosReaderAutoscroll = "reader_autoscroll"
    static let iosReaderCadence = "reader_cadence"

    // PDF reader (iOS-native)
    static let iosPdfReaderBack = "ios_pdf_reader_back"

    // CBZ reader (iOS-native)
    static let iosCbzReaderBack = "ios_cbz_reader_back"

    // Shared reader controls
    static let readerBack = "reader_back"
    static let readerReady = "reader_ready"
    static let readerLoading = "reader_loading"
    static let readerErrorState = "reader_error_state"
    static let readerSettings = "reader_settings"
    static let readerSearchField = "search_field"
    static let readerSearchCount = "search_result_count"
    static let readerSearchPrev = "search_prev"
    static let readerSearchNext = "search_next"
    static let readerSearchClose = "reader_search_close"
    static let readerTocPanel = "toc_panel"
    static let readerFootnotePopup = "footnote_popup"
    static let readerFootnoteClose = "footnote_close"
    static let readerReturnCard = "return_to_position_card"
    static let readerReturnBack = "return_to_position_back"
    static let readerReturnDismiss = "return_to_position_dismiss"

    // Readaloud player
    static let readaloudMiniPlayer = "readaloud_mini_player"
    static let readaloudPlayPause = "readaloud_play_pause"
    static let readaloudClose = "readaloud_close"
    static let readaloudPrevChapter = "readaloud_prev_chapter"
    static let readaloudNextChapter = "readaloud_next_chapter"

    // Audiobook player
    static let playerBack = "player_back"
    static let playerPlayPause = "player_play_pause"
    static let playerScrubber = "player_scrubber"
    static let playerPrevChapter = "player_prev_chapter"
    static let playerNextChapter = "player_next_chapter"
    static let playerChaptersPill = "player_chapters_pill"
    static let playerBookmarksPill = "player_bookmarks_pill"
    static let audiobookRewind = "audiobook-rewind"
    static let audiobookForward = "audiobook-forward"
    static let audiobookSpeedPill = "audiobook_speed_pill"
    static let bookmarkDialog = "bookmark_dialog"
    static let bookmarkTitleField = "bookmark_title_field"
    static let bookmarkSave = "bookmark_save"

    // Annotations panel
    static let annotationsBack = "annotations_back"
    static let showAllAnnotations = "show-all-annotations"
    static let annotationSearchResults = "annotation-search-results"

    // Settings
    static let settingsBack = "settings_back"

    // Sources / onboarding
    static let sourcesAddSource = "SourcesSection.AddSource"
    static let localFilesAddFolder = "LocalFilesSourceRow.AddFolder"
    static let localFilesConfirmRemoveFolder = "LocalFilesSourceRow.ConfirmRemoveFolder"

    // Add Local Files screen
    static let addLocalFilesDone = "AddLocalFiles.Done"
    static let addLocalFilesPickAgain = "AddLocalFiles.PickAgain"

    // Downloads
    static let downloadsBack = "downloads_back"
    static let downloadsRemoveAll = "DownloadsScreen.RemoveAllDownloads"
    static let downloadsClearCached = "DownloadsScreen.ClearAllCached"

    // Dynamic tag helpers (must match Kotlin fun counterparts)
    static func navDrawerLibrary(id: String) -> String { "nav_drawer_library_\(id)" }
    static func navDrawerSource(id: String) -> String { "nav_drawer_source_\(id)" }
    static func libraryBookItem(index: Int) -> String { "library_book_item_\(index)" }
    static func settingsTrailing(trailing: String) -> String { "settings-trailing-\(trailing)" }
}
