# Riffle — Domain Glossary

**Riffle** is an Android app (min API 24 / Android 7.0) for reading ebooks from a self-hosted Audiobookshelf server.

## Terms

### Server
A user-configured remote content source (URL + credentials). Two types exist: **ABS Server** (an Audiobookshelf instance) and **Storyteller Server** (a Storyteller instance — see [Storyteller Server]). Multiple Servers of either type can be added; the user switches between them manually via the Server Switcher. All local data (Cache, Downloads, Annotations, progress) is scoped to a Server. The reading experience (formatting, themes, navigation, sync) is identical regardless of Server type — a book from Storyteller is read like any other book, with optional Readaloud controls if available.

### Storyteller Server
A user-configured [Storyteller](https://storyteller-platform.gitlab.io/storyteller) server instance — a self-hosted service that aligns ebooks and audiobooks to produce EPUB 3 files with Media Overlays (SMIL). Behaves as a peer to ABS Server: surfaces its own Libraries, Library Items, and metadata; participates in the Navigation Drawer and Server Switcher exactly like ABS.

**Riffle uses Storyteller exclusively as a Readaloud provider.** A Library Item from a Storyteller Server is always a completed [Readaloud] — the aligned EPUB + audio output of the Storyteller pipeline. Plain ebooks and plain audiobooks are out of scope for the Storyteller integration: ABS already covers those formats, and ingesting bare Storyteller content would duplicate that role with a strictly weaker reading/listening experience. Storyteller content that is **not yet a completed Readaloud** (uploaded but unaligned, in-progress alignment, alignment failed) is filtered out at the network layer and never appears in the Readaloud Library.

### Readaloud
Storyteller's term for an aligned ebook+audiobook: an EPUB 3 with Media Overlays (SMIL) that map text fragments to audio timestamps. When a book is a Readaloud, the reader exposes audio playback controls and visibly highlights the text segment being read; the book remains fully readable without playback. Every Library Item from a Storyteller Server is a Readaloud intrinsically. For a Library Item from an ABS Server, Readaloud playback becomes available when a behind-the-scenes match to a Storyteller book exists — the matching itself is not surfaced as a user concept.

### Library
A navigation surface in Riffle for a set of Library Items. Every Library is backed by exactly one Server. For an [ABS Server], Riffle exposes each of the server's book Libraries 1:1 (`mediaType=book`; podcast libraries excluded). For a [Storyteller Server] — which has no native library concept — Riffle exposes a single synthetic Library: the [Readaloud Library]. The user decides which Libraries are visible in the Navigation Drawer via Library Visibility Preferences.

### Readaloud Library
A synthetic Library that surfaces all Library Items on a Storyteller Server. Present whenever a Storyteller Server is configured. Every Library Item in the Readaloud Library is a [Readaloud] by definition. A Storyteller book that is also matched to an item on an ABS Server appears both here and in its ABS Library — same book, two entry points; the reader is identical from either side. Name shown to the user TBD ("Readalouds" is the working label).

Storyteller's API only exposes `cover, title, authors` per book — no description, year, publisher, genres, or taxonomy. For an **Unmatched** Storyteller book the Readaloud Library shows exactly those three fields. For a **Confirmed-matched** Storyteller book the displayed metadata is borrowed wholesale from the linked ABS Library Item (cover, title, author, description, published year, publisher, genres), which is the only path to richer display. Because Storyteller has no Series or Collections endpoints, the Readaloud Library renders the standard Library Tab Bar with **only the All Books tab visible** — Home / Series / Collections are hidden for Storyteller-backed Libraries.

### Navigation Drawer
The primary navigation surface. Contains: the active Server name in a header (tappable → Server Switcher dropdown), the server-ordered list of visible Libraries for the active Server, a Downloads entry, and a Settings entry — in that order. Replaces the standalone LibraryListScreen and ServerListScreen as the main navigation entry point. On a fresh install with no Servers configured, the app opens directly to AddServerScreen; after the first Server is added, it navigates to the first Library in the Library Visibility Preferences list.

### Server Switcher
A dropdown triggered from the Navigation Drawer header. Lists all configured Servers; tapping one makes it the active Server and loads its Libraries. Read-only — adding and removing Servers both live in Settings.

### Library Visibility Preferences
A per-Server, user-managed set of hidden Libraries. Determines which Libraries appear in the Navigation Drawer. All Libraries are visible by default; the user can hide individual Libraries. At least one Library must remain visible — the last visible Library's toggle cannot be turned off. Library order follows the server-defined order. When opening a Server (on first launch, after adding a Server, or when switching Servers), the app navigates to the first non-hidden Library in the list.

### Series
A named, ordered grouping of Library Items within a Library. Defined on the ABS server (e.g. "The Stormlight Archive").

### Collection
A user-defined, unordered grouping of Library Items within a Library. Distinct from Series — not necessarily sequential. Library-scoped on the ABS server: shared across all users with access to the Library.

### Playlist
A per-user, per-Library ordered list of Library Items on the ABS server. Unlike a Collection, a Playlist is scoped to `(userId, libraryId)` and is not visible to other users. Accepts any Library Item, including ebook-only items. **To Read** is backed by a Playlist (see [ADR 0019](adr/0019-to-read-as-playlist.md)).

### To Read
A per-user, per-Library wishlist of Library Items the user intends to read. Implemented as an ABS Playlist named `To Read`, looked up by name and find-or-created on first use (see [ADR 0019](adr/0019-to-read-as-playlist.md), which supersedes [ADR 0018](adr/0018-to-read-as-named-collection.md)). Toggled via a bookmark icon on the Library Item Detail Screen (third 40dp circular icon in the action row, between mark-read and download). Filled bookmark = in the list, outline = not in the list.

Surfaced as a dedicated **To Read** tab in the library screen, positioned between Home and Series. The tab shows a grid of the user's queued Library Items; empty state reads "Nothing in To Read".

App-managed rules:
- **Find-or-create by name.** If the user renames the playlist on the server, the next toggle creates a new "To Read" playlist; the renamed one is left alone.
- **Per-Library, not global.** A user with multiple Libraries has one "To Read" playlist per Library.
- **Read transitions remove from To Read.** Any transition of a Library Item to the Read state — manual mark-read, or future auto-finish detection — removes the item from "To Read". The reverse is not enforced: toggling To Read on a Read book does not clear the Read flag (a legitimate re-read signal).
- **Empty playlists are auto-deleted by ABS.** Removing the last item deletes the playlist server-side; the next add transparently creates a fresh one.
- **Optimistic, no queueing.** Taps flip the icon immediately, fire the request, and revert with a snackbar on failure. Offline taps fail with a snackbar — there is no durable mutation queue (yet; see notes on a future unified sync mechanism).

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
Reconciliation of reading position between the app and every remote position holder for the open book. Applies to all supported formats (EPUB, PDF, and any future formats).

For an ABS-only book the cycle has one remote (ABS ebook progress). For a matched book with [Readaloud] there are three remote position holders: **ABS ebook progress** (CFI), **ABS audiobook progress** (seconds offset), and **Storyteller position** (Readium Locator anchored to Storyteller's publication). All three are first-class peers — any of them can win an inbound jump, and a local change is pushed to all three. Three-peer sync runs regardless of which side the user opened the book from (ABS or Readaloud), once the small sync prerequisites (Storyteller EPUB bundle + cross-EPUB index) are cached. Readaloud audio playback remains an explicit Readaloud-side action with its own download.

- **Cycle:** every ~30 s and immediately on reader resume, run a GET-then-maybe-PATCH cycle for every applicable remote.
- **Unified canonical position:** the reader has a single canonical position with a single `localUpdatedAt`. Each remote position is convertible to and from the canonical reader position (audiobook seconds ↔ text via SMIL; ABS-EPUB CFI ↔ Storyteller-EPUB position via the cross-EPUB index; Storyteller position is native).
- **Inbound (last-update-wins, single winner):** fetch all applicable remotes and their `lastUpdate` timestamps. Identify the absolute newest among `{localUpdatedAt, ...remotes}`. If a remote wins, jump the reader to its converted canonical position once and set `localUpdatedAt = winner.lastUpdate`.
- **Outbound:** PATCH every remote that is now stale relative to the canonical position. For a matched book that is one cycle → three writes (ABS ebook, ABS audiobook, Storyteller).
- **Per-target failure isolation:** failures are isolated per remote. A GET failure for one target skips that target's inbound check only; the other targets still proceed. A PATCH failure leaves that target stale for the next cycle.
- **No conflict prompt:** the last-update-wins rule is applied silently in all cases.

### Formatting Preferences
User-controlled reading display settings. Scope varies by format:
- **EPUB:** font size, theme (Light / Dark / Sepia / [Auto Theme]), font family (system fonts + Literata, Merriweather, OpenDyslexic), justify text (on/off toggle, default off), line spacing, margins, reading orientation (paginated / continuous scroll).
- **PDF:** theme (as colour filter, same value set as EPUB including [Auto Theme]), scroll direction (paged / continuous), zoom persistence.

### Auto Theme
A theme value that sits alongside Light, Dark, DarkDim, and Sepia in the [Formatting Preferences] theme picker. Selected like any other theme — globally as the default or per-book as an override — but resolves at render-time to one of the four concrete themes according to the [Theme Schedule]. The chip in the formatting panel uses a split day/night swatch (half day-theme background, half night-theme background) so the user can see which two palettes the schedule will alternate between. A book pinned to a concrete theme is unaffected by the schedule; a book pinned to Auto follows it.

### Theme Schedule
A global, user-configured pair of clock times and theme picks that drive the [Auto Theme]. Four fields: **day-start**, **night-start**, **day-theme**, **night-theme**. The two theme picks are restricted to the four concrete themes (Light, Dark, DarkDim, Sepia) — Auto cannot nest inside Auto. Interpreted on the device's local clock as two arcs on a 24-hour circle: the night arc runs clockwise from night-start to day-start and may cross midnight. If day-start equals night-start, the schedule degenerates to always-day. Defaults on first opt-in: 07:00, 21:00, Light, Dark. Applies uniformly to EPUB and PDF reading. Boundary crossings during an open reading session repaint live — a timer fires at the next boundary and the navigator reapplies preferences without the user closing the book. The four fields are editable only on the full-screen Settings panel; the in-reader formatting panel just shows the Auto chip as a selectable theme. See [ADR 0022](adr/0022-auto-reader-theme-clock-scheduled-fifth-enum.md).

### EPUB CFI
An EPUB Canonical Fragment Identifier — a string of the form `epubcfi(/6/N!<docPath>)` that pinpoints an exact location within an EPUB chapter. The spine step (`/6/N`) identifies the chapter; the document path after `!` identifies a node and character offset within that chapter's HTML. Two CFI dialects exist in practice: Readium emits XPath-style node addresses; epub.js (ABS's web reader) emits character-count-based addresses. The two are structurally incompatible. See ADR 0013.

### EPUB CFI Translator
The layer (`EpubCfiTranslator`) responsible for converting between Readium's native position representation and the character-count-based EPUB CFI format used by ABS/epub.js. All `ebookLocation` values crossing the Riffle↔ABS API boundary — in both directions — must pass through this translator. Inbound: server CFI → within-chapter progression → Readium Locator. Outbound: Readium within-chapter progression → CFI doc path → full CFI string. `ebookProgress` (book-wide float) is a separate display field and does not go through the translator. See ADR 0013.

### Annotation
Umbrella term for the three user-authored marks attached to a Library Item: [Highlight], [Note], and [Bookmark]. All three share one storage model and one [Annotation Sync] format. Each carries a stable client-generated ID, creation and last-modified timestamps, and a device-origin tag, and anchors to an **EPUB CFI** (the same coordinate family ABS already stores in `mediaProgress.ebookLocation` and that the [EPUB CFI Translator] operates on). The CFI is the load-bearing, irreversible anchor choice; every Annotation additionally stores the surrounding text snippet and chapter href as a human-readable fallback and re-anchoring aid if the EPUB is re-uploaded.

Annotations are an **ABS-book capability**, anchored in the **ABS EPUB's** coordinate system, and keyed by the ABS Library Item. A [Storyteller Server] is only a [Readaloud] provider, so a Storyteller-only (unmatched) book carries no Annotations, and — because the ABS and Storyteller editions are different EPUB files with different CFIs (see [ADR 0019](adr/0019-three-peer-unified-canonical-progress-sync.md)) — Annotations are created and displayed only while reading the **ABS side** in v1. Surfacing them on the Readaloud side (via ADR 0019's cross-EPUB index) is a deferred enhancement.

### Annotation Sync
The mechanism by which [Annotation]s roam between devices. The local Room store is always the primary, queryable store; Sync is an optional layer on top, reached through a single `AnnotationSyncTarget` abstraction (`list / read / write-own-file`) so the backing store is swappable without touching the format or the schema. The on-the-wire format is the **W3C Web Annotation Data Model** (JSON-LD), with a `riffle:` extension namespace carrying the merge-critical fields the standard omits (`device`, `updatedAt`, `deleted` tombstone). Two target *kinds* are anticipated: **blob-store** targets (a folder of one file per device per book, merged client-side by per-record last-write-wins — `deviceId` names the file, the annotation UUID is the identity) and **record-store** targets (per-record CRUD with server-side merge). **v1 ships local-only — no target implemented** — but the schema is sync-ready so enabling a target later is additive, never a migration. Abusing the ABS bookmark API as a record-store target was considered and **rejected**: it pollutes the audiobook-bookmark surface that Riffle itself will render once it becomes an audiobook player. A native ABS annotation endpoint is the eventual target the format is designed to slot into.

### Highlight
A marked **text range** in an EPUB, stored with a colour and an optional [Note]. The colour is a token from a small fixed palette (yellow — the default — green, blue, pink), not a freeform value; the reader's theme system owns the light/dark RGB mapping so a Highlight stays legible in any theme. Anchored to a **CFI range** (start + end) over the selected text. An [Annotation].

### Note
A user-written text comment on a [Highlight], annotating that range. In v1 a Note is **not a standalone entity** — it is an optional field on a Highlight, so a Highlight may exist with no Note, but a Note never exists without a Highlight. (A future standalone "margin note" at a bare location — a point anchor with a body — is deferred.) An [Annotation].

### Bookmark
A saved reading location with no text selection — a pure location marker, distinct from a [Highlight]. Anchored to a **CFI point** at the top-of-viewport text of the current page (the reader's `currentLocator`, the same canonical position [Progress Sync] tracks); reflowable EPUBs have no fixed pages, so a Bookmark marks a text position, never a page number. An [Annotation].

### Table of Contents (TOC)
The navigable chapter and subchapter structure of a Library Item, derived from the EPUB or PDF's own structure.

### Chapter Navigation Rail
A thin UI element fixed at the bottom of the reader screen. Visualises the current chapter's subchapter breakdown and the user's position within it. Tapping a segment jumps to that subchapter.

### Screen Wake Lock
A global user preference (default: on) that prevents the device screen from sleeping while a book is open. Applies to both EPUB and PDF readers. Configured once for all books — not a per-book override.

### Volume Key Navigation
A global user preference (default: on) that enables page turns via the device's hardware volume buttons while reading. Applies to both EPUB and PDF readers. Volume Down advances to the next page; Volume Up goes to the previous page. When a panel (TOC or Formatting) is open, volume key presses are swallowed — no navigation occurs and the system volume UI is suppressed. Includes a secondary preference, **Invert Volume Keys** (default: off), which swaps the direction mapping so Volume Down goes to the previous page and Volume Up to the next.

### Immersive Mode
A reader state in which the app's TopAppBar and Android's system bars (status bar + navigation bar) are both hidden, giving the reading content the full screen. Toggled by tapping the reading content area. The reader always opens in immersive mode. Tapping while immersive restores the TopAppBar and the status bar (clock, battery); the navigation bar remains hidden to avoid reflowing the reader layout. An edge-swipe restores all system bars permanently (Android BEHAVIOR_DEFAULT). Not persisted across reading sessions — closing and reopening a book always starts in immersive mode. Device rotation preserves the current immersive state: if the user has revealed chrome before rotating, it stays revealed; if they were in immersive mode, it stays immersive.

### Book Search
An in-EPUB text search available while reading. Activated via a search icon in the reader's TopAppBar (leftmost of the three action icons: Search → TOC → Formatting). Tapping the icon transforms the TopAppBar in-place: the title and icons collapse and a search field with a ✕ button expands to fill the bar. Searches the full publication (all chapters) via Readium's `SearchService`. Results are highlighted directly in the reading content using Readium's `DecoratorService`; the active match is distinguished from others. A match count ("3 of 24") and prev/next arrows appear below the field for result navigation. Search triggers live with a short debounce. No results shows "No results" in the match count area. Tapping ✕ collapses the bar and leaves the user at their current position. The back arrow always exits the reader regardless of search state. Progress Sync updates normally during search navigation. Applies to EPUB only — absent from the PDF reader.

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

### Tablet Layout
The variant of the app's UI applied when the current window's width is in Material 3's **Expanded** size class (≥ 840dp). Compact (< 600dp) and Medium (600–839dp) windows continue to use the standard phone UI — so a phone in landscape, an unfolded foldable, and a small tablet in portrait all stay on the phone layout. The Tablet Layout differs from the phone layout in three places: the Navigation Drawer becomes a Permanent Navigation Drawer; the Library Item Detail Screen splits into a two-column layout (cover and action row in a fixed left pane, metadata and description in an independently scrolling right pane); and single-column list/form screens (Settings, Downloads Screen, AddServerScreen, Library Visibility Preferences) are width-capped and centred in the content pane. The Library Tab Bar remains pinned to the bottom, cover grids continue to use adaptive cell sizing (with a larger minimum cell size on Expanded), and the reader is unchanged. The layout switches reactively on configuration change — unfolding a foldable, resizing a ChromeOS window, or entering split-screen all re-evaluate the size class.

### Permanent Navigation Drawer
The Tablet Layout variant of the Navigation Drawer. Pinned to the leading edge of the window and always visible — there is no hamburger affordance and no scrim. Contents and ordering are identical to the modal Navigation Drawer used on phones: active Server header (tappable → Server Switcher), visible Libraries, Downloads, Settings.
