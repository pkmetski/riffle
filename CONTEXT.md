# Riffle — Domain Glossary

**Riffle** is an Android app (min API 24 / Android 7.0) for reading ebooks from a self-hosted Audiobookshelf server.

## Terms

### Server
A user-configured Audiobookshelf server instance (URL + credentials). Multiple Servers can be added; the user switches between them manually. All local data (Cache, Downloads, Annotations) is scoped to a Server.

### Library
A top-level collection on the connected ABS server. Riffle surfaces all Libraries whose `mediaType` is `book` (podcast libraries are excluded). Because ABS does not expose a reliable library-level signal to distinguish ebook libraries from audiobook libraries, all book Libraries are shown. The user decides which Libraries are visible in the navigation drawer via Library Visibility Preferences.

### Navigation Drawer
The primary navigation surface. Contains: the active Server name in a header (tappable → Server Switcher dropdown), the server-ordered list of visible Libraries for the active Server, a Downloads entry, and a Settings entry — in that order. Replaces the standalone LibraryListScreen and ServerListScreen as the main navigation entry point. On a fresh install with no Servers configured, the app opens directly to AddServerScreen; after the first Server is added, it navigates to the first Library in the Library Visibility Preferences list.

### Server Switcher
A dropdown triggered from the Navigation Drawer header. Lists all configured Servers; tapping one makes it the active Server and loads its Libraries. Read-only — adding and removing Servers both live in Settings.

### Library Visibility Preferences
A per-Server, user-managed set of hidden Libraries. Determines which Libraries appear in the Navigation Drawer. All Libraries are visible by default; the user can hide individual Libraries. At least one Library must remain visible — the last visible Library's toggle cannot be turned off. Library order follows the server-defined order. When opening a Server (on first launch, after adding a Server, or when switching Servers), the app navigates to the first non-hidden Library in the list.

### Series
A named, ordered grouping of Library Items within a Library. Defined on the ABS server (e.g. "The Stormlight Archive").

### Collection
A user-defined, unordered grouping of Library Items within a Library. Distinct from Series — not necessarily sequential.

### Library Item
An entry within a Library on the ABS server. Includes metadata (title, author, cover). May or may not have an associated ebook file. May belong to a Series, a Collection, or neither. A Library Item with no ebook file is an Unsupported Library Item.

### Unsupported Library Item
A Library Item that has no ebook file on the server (e.g. an audiobook). Displayed in the library list as dimmed. Tapping it opens the Library Item Detail Screen, which explains why the item cannot be read.

### Library Item Detail Screen
A screen that displays the full metadata for a single Library Item: cover, title, author, series membership, description, published year, genres, and publisher. Also shows reading progress and local availability via a Download Button. For supported items, contains a Read button that launches the appropriate reader (EPUB or PDF). For Unsupported Library Items, the Read button is absent and the screen explains that no ebook file is available. Reachable by tapping any Library Item card in the library list, a Series detail, or a Collection detail. Back-navigating from the reader returns here before returning to the list.

### Download Button
An icon-only button on the Library Item Detail Screen that manages the local copy of an item. Cycles through three visible states: outline arrow (not downloaded — tap to download), spinner (download in progress), filled arrow (downloaded — tap to remove). Removing a download is immediate with an Undo snackbar; no confirmation dialog. Applies to both EPUB and PDF items.

### Cache
A local copy of a Library Item's EPUB file that the app creates automatically when the user opens a book. Stored in a clearable system cache directory. Available for offline reading as long as it has not been evicted.

### Download
A local copy of a Library Item's EPUB file that the user explicitly requests. Stored in a permanent directory that is never auto-cleared. Available for offline reading indefinitely.

### Downloads Screen
A dedicated screen reachable from the Navigation Drawer. Lists all locally available Library Items in two sections: Downloaded (permanent, user-requested) and Cached (auto-created on open, may be evicted). Each item shows the appropriate indicator icon. Provides a "Remove all" action per section; bulk removal of Downloads requires a confirmation dialog. The Cached section's "Clear all" action has no confirmation.

### Offline Mode
The state in which the app cannot reach the ABS server — either because the device has no network or because the server itself is unreachable. Detected reactively: the banner appears after a request fails or when ConnectivityManager reports no network. In this state, the app reads from Cache or Download. Reading progress recorded during Offline Mode is queued for Progress Sync.

### Reading Session
A server-side record of a single continuous reading period. Opened via the ABS API when the user starts reading, updated periodically, and closed when the user leaves the reader or the app backgrounds. Feeds server-side reading statistics (time read, pages per day, streaks).

### Reading Statistics
Aggregated reading data fetched from the ABS server — time spent reading, books finished, current streaks. Populated by Reading Sessions pushed from the app.

### Progress Sync
Bidirectional reconciliation of reading position between the app and the ABS server. Applies to all supported formats (EPUB, PDF, and any future formats).

- **Cycle:** on every periodic tick (~30 seconds) and immediately on reader resume, the app runs a GET-then-maybe-PATCH cycle for the open book.
- **Inbound check (GET first):** fetch server position and its `lastUpdate` timestamp via `GET /api/me/progress/:libraryItemId`. Compare against `localUpdatedAt` — the local timestamp updated on every position change (page turn, scroll), persisted to the local DB.
- **Last-update-wins:** if `server.lastUpdate > localUpdatedAt`, jump the reader to the server's position silently and set `localUpdatedAt = server.lastUpdate`. If `localUpdatedAt >= server.lastUpdate`, PATCH local position to server.
- **Offline behaviour:** if the GET fails (server unreachable), skip the entire cycle — no PATCH is attempted. `localUpdatedAt` continues to advance with page turns. When connectivity returns the next cycle runs normally; `localUpdatedAt` will be newer than the server's stale timestamp, so local position is pushed.
- **No conflict prompt:** there is no user-facing conflict resolution UI. The last-update-wins rule is applied silently in all cases.

### Formatting Preferences
User-controlled reading display settings. Scope varies by format:
- **EPUB:** font size, theme (Light / Dark / Sepia), font family (system fonts + Literata, Merriweather, OpenDyslexic), line spacing, margins, reading orientation (paginated / continuous scroll).
- **PDF:** theme (as colour filter), scroll direction (paged / continuous), zoom persistence.

### EPUB CFI
An EPUB Canonical Fragment Identifier — a string of the form `epubcfi(/6/N!<docPath>)` that pinpoints an exact location within an EPUB chapter. The spine step (`/6/N`) identifies the chapter; the document path after `!` identifies a node and character offset within that chapter's HTML. Two CFI dialects exist in practice: Readium emits XPath-style node addresses; epub.js (ABS's web reader) emits character-count-based addresses. The two are structurally incompatible. See ADR 0013.

### EPUB CFI Translator
The layer (`EpubCfiTranslator`) responsible for converting between Readium's native position representation and the character-count-based EPUB CFI format used by ABS/epub.js. All `ebookLocation` values crossing the Riffle↔ABS API boundary — in both directions — must pass through this translator. Inbound: server CFI → within-chapter progression → Readium Locator. Outbound: Readium within-chapter progression → CFI doc path → full CFI string. `ebookProgress` (book-wide float) is a separate display field and does not go through the translator. See ADR 0013.

### Highlight
A marked text range in an EPUB, stored with a colour and an optional Note. Anchored to an EPUB CFI position.

### Note
A user-written text annotation attached to a Highlight or a specific location in a Library Item.

### Bookmark
A saved position in a Library Item with no text selection — a pure location marker. Distinct from a Highlight.

### Table of Contents (TOC)
The navigable chapter and subchapter structure of a Library Item, derived from the EPUB or PDF's own structure.

### Chapter Navigation Rail
A thin UI element fixed at the bottom of the reader screen. Visualises the current chapter's subchapter breakdown and the user's position within it. Tapping a segment jumps to that subchapter.

### Screen Wake Lock
A global user preference (default: on) that prevents the device screen from sleeping while a book is open. Applies to both EPUB and PDF readers. Configured once for all books — not a per-book override.

### Volume Key Navigation
A global user preference (default: on) that enables page turns via the device's hardware volume buttons while reading. Applies to both EPUB and PDF readers. Volume Down advances to the next page; Volume Up goes to the previous page. When a panel (TOC or Formatting) is open, volume key presses are swallowed — no navigation occurs and the system volume UI is suppressed. Includes a secondary preference, **Invert Volume Keys** (default: off), which swaps the direction mapping so Volume Down goes to the previous page and Volume Up to the next.

### Immersive Mode
A reader state in which the app's TopAppBar and Android's system bars (status bar + navigation bar) are both hidden, giving the reading content the full screen. Toggled by tapping the reading content area. Chrome and system bars reappear together — either via another tap or by an edge-swipe that temporarily reveals the system bars. The reader always opens in immersive mode. Not persisted across reading sessions — closing and reopening a book always starts in immersive mode. Device rotation preserves the current immersive state: if the user has revealed chrome before rotating, it stays revealed; if they were in immersive mode, it stays immersive.

### Supported Formats
EPUB (reflowable) and PDF (fixed-layout). Rendered via the Readium Kotlin SDK (EPUB navigator + Pdfium adapter).

### Library Tab Bar
The bottom navigation surface within a Library screen. Contains four icon-only tabs — Home, Series, Collections, and All Books — scoped to the currently viewed Library. Replaces the Plex-style single scrollable feed as the primary way to browse a Library's content. The Navigation Drawer remains the navigation surface for switching Libraries, accessing Downloads, and Settings.

### Home Tab
The first tab in the Library Tab Bar. Displays two sections: In Progress (books the user has started but not finished) and Completed (books the user has finished). Each section shows a horizontal cover grid with a "See All" link to the full section list.

### Series Tab
The second tab in the Library Tab Bar. Displays all Series in the current Library as a full cover grid.

### Collections Tab
The third tab in the Library Tab Bar. Displays all Collections in the current Library as a full cover grid.

### All Books Tab
The fourth tab in the Library Tab Bar. Displays every Library Item in the current Library as a full cover grid.
