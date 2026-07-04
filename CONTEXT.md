# Riffle — Domain Glossary

**Riffle** is an Android app (min API 24 / Android 7.0) for reading ebooks from a self-hosted Audiobookshelf server.

## Terms

### Server
A user-configured remote content source (URL + credentials). Two types exist: **ABS Server** (an Audiobookshelf instance — the only browsable content source) and **Storyteller Server** (a Storyteller instance used purely as a readaloud backend — see [Storyteller Server]). Multiple ABS Servers can be added and the user switches between them via the Server Switcher; **Storyteller Servers are managed only in Settings and never appear in the Server Switcher or Navigation Drawer.** Annotations and reading progress are scoped to a Server. [Cache] and [Download] live in a single **device-global** store — one set of directories shared across all Servers and Server types, with no separate Storyteller-specific location and no per-Server or per-Server-type caching configuration (see [ADR 0024](docs/adr/0024-drop-per-server-audio-cache-cap.md)). Today both the file store **and the `library_items` database table** key entries by **Library Item id alone**, which means identically-numbered items on different Servers collide (Storyteller assigns sequential numeric ids that restart per Server). Reading progress already keys by (Server, Library Item); the item store and table do not. Fixing this end-to-end is a known, deferred change tracked in [ADR 0025](docs/adr/0025-key-local-stores-by-server-and-item.md). (A Storyteller Server's books enter `library_items` only as matcher input — never as browsable entries — but they still occupy the same id space, so the collision applies to them too.) An ABS book reads identically whether or not it has a [Readaloud]; when one is linked, the reader gains optional audio playback controls.

### Storyteller Server
A user-configured [Storyteller](https://storyteller-platform.gitlab.io/storyteller) server instance — a self-hosted service that aligns ebooks and audiobooks to produce EPUB 3 files with Media Overlays (SMIL). Riffle uses it as the **processor, producer, and provider of [Readaloud]s, and nothing else**: it is a **Settings-only backend** that contributes no Libraries, has no browsable surface, and never appears in the Server Switcher or Navigation Drawer (see [ADR 0026](docs/adr/0026-storyteller-as-settings-only-readaloud-backend.md)). Its only effects in Riffle are (1) supplying the readaloud alignment — the [Readaloud Sidecar] (SMIL + aligned chapter text), or the full synced bundle as a fallback (see [ADR 0028](docs/adr/0028-readaloud-audio-streams-from-abs-bundle-is-fallback.md)) — and the position record for an ABS Library Item that is linked to it, and (2) feeding the matcher its book metadata. A Storyteller Server is useful only **alongside at least one [ABS Server]** — a Storyteller-only deployment has nothing to read.

Riffle only ever consumes **completed Readalouds**: Storyteller content that is not yet aligned (uploaded but unaligned, in-progress, alignment failed) is filtered out at the network layer (`?synced=true`) and never enters Riffle. Storyteller's API exposes only `cover, title, authors` per book; that metadata is used **solely as matcher input** and is never displayed to the user.

### Readaloud
Storyteller's term for an aligned ebook+audiobook: an EPUB 3 with Media Overlays (SMIL) that map text fragments to audio timestamps. In Riffle a Readaloud is **a capability attached to an [ABS Server] Library Item**, not a standalone item: it becomes available when a behind-the-scenes match links that ABS book to a Storyteller book (see [ADR 0021], [ADR 0026]). When a linked Readaloud is present, the reader — still displaying the **ABS EPUB** — exposes audio playback controls and visibly highlights the text segment being read (the highlight is anchored to sentence text from the [Readaloud Sidecar] via the cross-EPUB index; the audio is **streamed from the matched [ABS Server] audiobook** when the match is streaming-eligible, or played from the Storyteller synced bundle as a fallback — see [ADR 0028](docs/adr/0028-readaloud-audio-streams-from-abs-bundle-is-fallback.md)); the book remains fully readable without playback. A Readaloud is **streaming-eligible** when its ABS ebook and audiobook are both linked and the audiobook passes an identity check (the audiobook ABS serves is the recording Storyteller aligned against); otherwise audio falls back to the bundle download. The matching itself is not surfaced as a user concept.

### Readaloud Sidecar
The small alignment artifact for a [Readaloud]: the Media Overlay SMIL plus the aligned Storyteller chapter text, extracted from the Storyteller synced EPUB **without its audio** (~1 MB, versus hundreds of MB for the full bundle). It supplies the text-anchored highlight and the Storyteller side of the cross-EPUB index, so a streaming-eligible Readaloud needs only the Sidecar plus the [ABS Server] audiobook — never the full bundle. See [ADR 0028](docs/adr/0028-readaloud-audio-streams-from-abs-bundle-is-fallback.md).

### Library
A navigation surface in Riffle for a set of Library Items. Every Library is backed by exactly one [ABS Server] — Riffle exposes each of the server's book Libraries 1:1 (`mediaType=book`; podcast libraries excluded). [Storyteller Server]s contribute no Libraries. The user decides which Libraries are visible in the Navigation Drawer via Library Visibility Preferences.

### Navigation Drawer
The primary navigation surface. Contains: the active Server name in a header (tappable → Server Switcher dropdown), the server-ordered list of visible Libraries for the active Server, a Downloads entry, and a Settings entry — in that order. Replaces the standalone LibraryListScreen and ServerListScreen as the main navigation entry point. On a fresh install with no Servers configured, the app opens directly to AddServerScreen; after the first Server is added, it navigates to the first Library in the Library Visibility Preferences list.

### Server Switcher
A dropdown triggered from the Navigation Drawer header. Lists all configured [ABS Server]s; tapping one makes it the active Server and loads its Libraries. [Storyteller Server]s are not listed — they contribute no Libraries. Read-only — adding and removing Servers both live in Settings.

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

### Audiobook
A Library Item that is **listenable**: its ABS `media` carries audio (`hasAudio` true), and Riffle plays that audio by streaming it **directly from the ABS Server** (not from a Storyteller bundle — see [Readaloud], [ADR 0023]). An Audiobook need not have an ebook: the common case is an **audiobook-only** item (`ebookFormat = Unsupported`, `hasAudio` true), and the audiobook player must never assume an ebook exists. A **combined** item — one ABS item carrying both an EPUB and audio — is both readable and listenable, and surfaces both a Read and a Listen affordance. Distinct from a [Readaloud], which is text+audio *aligned* (SMIL) and sourced from Storyteller; an Audiobook is plain audio from ABS with no required text alignment. Played in the [Audiobook Player].

### Audiobook Player
The full-screen surface that plays an [Audiobook]. Shows the (square) cover, title/author, and the current chapter title. Its scrubber is a **seekable chapter map**: one continuous draggable track over the whole book whose tick marks are the chapter boundaries (ABS `media.chapters`) and whose current chapter is banded; the seek handle is a thin vertical playhead. Time is shown twice — **per chapter** and **whole book** — mirroring the official Audiobookshelf app. The transport cluster is centered with play/pause in the middle — rewind 15s, previous chapter, play/pause, next chapter, forward 30s — and the playback-speed control sits apart in a secondary utility row (not in the transport row). Previous/next-chapter are enabled only when the audiobook has chapter markers; a chapterless single-file audiobook shows them disabled and its scrubber degrades to a plain track (no ticks/band, whole-book time only). The player is **audio-led**: the live audio position drives the book's canonical position and propagates outward through [Progress Sync] exactly as a reading position does (see [ADR 0029]). It streams audio **directly from the [ABS Server]** — not from a Storyteller bundle, even for a matched book (a bundle, when present, is consulted only to translate audio seconds to the text canonical). Launched by the **Listen** affordance on the [Library Item Detail Screen]. Distinct from the Readaloud player, which is a translucent bar inside the reader; the two share no code in v1 (the Readaloud control set is the visual reference). The persistent now-playing mini-bar, offline audiobook download, a chapter-list jump sheet, sleep timer, and audiobook bookmarks are out of v1 scope.

### Readable / Listenable
The two independent capabilities a [Library Item] may have, replacing the single "supported" boolean. **Readable** = has an ebook file (EPUB or PDF) Riffle can open in the reader. **Listenable** = has audio Riffle can play in the [Audiobook Player] (an [Audiobook]). An item may be neither, either, or both: ebook-only (readable), audiobook-only (listenable), combined (both), or [Unsupported Library Item] (neither). The [Library Item Detail Screen]'s action row gates Read and Listen affordances on these two capabilities independently.

### Unsupported Library Item
A Library Item that is neither [Readable] nor [Listenable] — no ebook file *and* no audio Riffle can play (e.g. a metadata-only stub, or a podcast that slipped past the `mediaType=book` filter). Displayed in the library list as dimmed; tapping it opens the Library Item Detail Screen, which explains there is nothing to read or listen to. (Before Audiobook support, audiobook-only items were classed as Unsupported; they are now first-class [Audiobook]s.)

### Library Item Detail Screen
A screen that displays the full metadata for a single Library Item: cover, title, author, series membership (shown as `<series name> #<sequence>`), description, published year, genres, language, and publisher. Also shows reading progress and local availability via a Download Button. For supported items, contains a Read button that launches the appropriate reader (EPUB or PDF). For Unsupported Library Items, the Read button is absent and the screen explains that no ebook file is available. Reachable by tapping any Library Item card in the library list, a Series detail, or a Collection detail. Back-navigating from the reader returns here before returning to the list.

Several metadata values are **tappable facets** that navigate to a [Filtered Books Screen]: each author (the byline splits into one chip per author), each genre, the published year, and the language. The series membership taps through to the existing Series detail (matched on series name only, ignoring the `#<sequence>`). When the item has a [Readaloud], its badge is also tappable and leads to the "has a Readaloud" Filtered Books Screen — the only entry point to that view (so it is invisible when no books have a Readaloud).

### Filtered Books Screen
A navigation surface listing every Library Item in the **current Library** that matches a single metadata **facet**: an author, a published year, a genre, a language, or "has a [Readaloud]". Reached by tapping the corresponding value on the [Library Item Detail Screen]. Library-scoped and presented as a cover grid, mirroring Series and Collection detail. Unlike a [Series] or [Collection] (server-defined groupings), a facet match is computed **locally** over the synced Library Items, so it works offline (see [ADR 0027](docs/adr/0027-client-side-facet-filtering-over-synced-items.md)).

### Download Button
An icon-only button on the Library Item Detail Screen that manages the local copy of an item. Cycles through three visible states: outline arrow (not downloaded — tap to download), spinner (download in progress), filled arrow (downloaded — tap to remove). Removing a download is immediate, with no Undo and no confirmation dialog; re-downloading is a deliberate user action. (Undo was removed because for a Storyteller bundle it silently triggered a multi-hundred-MB re-download — the exact data burn the explicit-download model exists to prevent.) Applies to both EPUB and PDF items.

### Cache
A local copy of a Library Item's EPUB file that the app creates automatically when the user opens a book. Stored in a clearable system cache directory. Available for offline reading as long as it has not been evicted.

### Download
A local copy of a Library Item's EPUB file that the user explicitly requests. Stored in a permanent directory that is never auto-cleared. Available for offline reading indefinitely.

### Downloads Screen
A dedicated screen reachable from the Navigation Drawer, and the single source of truth for what is locally available and how much space it uses. Lists all locally available Library Items in two sections: Downloaded (permanent, user-requested) and Cached (auto-created on open, may be evicted). Each row shows its file size and the appropriate indicator icon, and is individually removable (immediate, no Undo, no per-item confirmation). Each section header shows the section's total size. Provides a "Remove all" action per section; bulk removal of Downloads requires a confirmation dialog, the Cached section's "Clear all" does not. There is no cache-size cap or other caching configuration — eviction of the Cached tier is OS-managed (see [ADR 0001](adr/0001-hybrid-cache-download-storage.md), [ADR 0024](adr/0024-drop-per-server-audio-cache-cap.md)).

### Offline Mode
The state in which the app cannot reach the ABS server — either because the device has no network or because the server itself is unreachable. Detected reactively: the banner appears after a request fails or when ConnectivityManager reports no network. In this state, the app reads from Cache or Download. Reading and listening progress recorded during Offline Mode is recorded locally and **durably reconciled when connectivity returns**, whether or not the book is reopened (see [ADR 0030](docs/adr/0030-durable-offline-progress-reconcile.md)). A server position that moved (and is newer) in the meantime is never overwritten.

### Reading Session
A server-side record of a single continuous reading period. Opened via the ABS API when the user starts reading, updated periodically, and closed when the user leaves the reader or the app backgrounds. Feeds server-side reading statistics (time read, pages per day, streaks).

### Reading Statistics
Aggregated reading data fetched from the ABS server — time spent reading, books finished, current streaks. Populated by Reading Sessions pushed from the app.

### Progress Sync
Reconciliation of reading position between the app and every remote position holder for the open book. Applies to all supported formats (EPUB, PDF, and any future formats).

For an ebook-only book the cycle has one remote (ABS ebook progress); for an [Audiobook]-only book, one remote (ABS audiobook progress). For a matched book with [Readaloud] there are **two** remote position holders: **ABS ebook progress** (CFI) and **ABS audiobook progress** (seconds offset). Both are first-class peers — either can win an inbound jump, and a local change is pushed to both. **Storyteller's own position record is not a peer** (see [ADR 0029](docs/adr/0029-audiobook-direct-abs-streaming-audio-led-sync.md)): the Storyteller bundle's SMIL is used only to *translate* between the two ABS records (audio seconds ↔ text), never written back to Storyteller. The book is opened from either the ABS reader (text-led canonical) or the [Audiobook Player] (audio-led canonical); the matched two-peer cycle runs once the sync prerequisites (**[Readaloud Sidecar]** + cross-EPUB index) are cached. Readaloud audio is streamed from the matched ABS audiobook when the match is streaming-eligible, or downloaded as the Storyteller bundle otherwise (see [ADR 0028](docs/adr/0028-readaloud-audio-streams-from-abs-bundle-is-fallback.md)); audiobook audio streams directly from ABS.

- **Cycle:** every ~30 s and immediately on reader resume, run a GET-then-maybe-PATCH cycle for every applicable remote.
- **Unified canonical position:** the open book has a single canonical position with a single `localUpdatedAt`. Each remote position is convertible to and from the canonical position (audiobook seconds ↔ text via the bundle's SMIL, made absolute over the concatenated audio files; ABS-EPUB CFI ↔ canonical natively). The cross-EPUB index aligns the Storyteller-EPUB SMIL coordinates to the displayed ABS EPUB.
- **Inbound (last-update-wins, single winner):** fetch all applicable remotes and their `lastUpdate` timestamps. Identify the absolute newest among `{localUpdatedAt, ...remotes}`. If a remote wins, jump to its converted canonical position once and set `localUpdatedAt = winner.lastUpdate`.
- **Outbound:** PATCH every remote that is now stale relative to the canonical position. For a matched book that is one cycle → two writes (ABS ebook, ABS audiobook).
- **Per-target failure isolation:** failures are isolated per remote. A GET failure for one target skips that target's inbound check only; the other targets still proceed. A PATCH failure leaves that target stale for the next cycle.
- **No conflict prompt:** the last-update-wins rule is applied silently in all cases.
- **Durable across Offline Mode:** progress recorded while offline is reconciled when connectivity returns even if the book is never reopened, still GET-before-PATCH so a newer server position is never overwritten (see [ADR 0030](docs/adr/0030-durable-offline-progress-reconcile.md)). For a matched book, reading and listening are the same activity and update both the ebook and audiobook position.

### Formatting Preferences
User-controlled reading display settings. Scope varies by format:
- **EPUB:** font size, theme (Light / Dark / Sepia / [Auto Theme]), font family (system fonts + Literata, Merriweather, OpenDyslexic), justify text (on/off toggle, default off), line spacing, margins, reading orientation (paginated / continuous scroll).
- **PDF:** theme (as colour filter, same value set as EPUB including [Auto Theme]), zoom persistence. PDF is paged-only today; a continuous scroll mode is not yet implemented.

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

Annotations are an **ABS-book capability**, anchored in the **ABS EPUB's** coordinate system, and keyed by the ABS Library Item. Since every book is read from the ABS EPUB (a linked [Readaloud] only overlays audio + highlight — see [ADR 0026](adr/0026-storyteller-as-settings-only-readaloud-backend.md)), Annotations are available during all reading, with or without a Readaloud.

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
A global user preference (default: on) that prevents the device screen from sleeping while a book is open. Applies to both EPUB and PDF readers. Configured once for all books — not a per-book override. Always held while [Auto-Scroll] is running, regardless of this preference — a sleeping screen would visibly break the hands-free reading session — and released the moment auto-scroll stops, returning to the user's configured behaviour.

### Cadence
A reader mode in which the highlighted sentence advances on its own at a user-set pace (words-per-minute), so the reader can read hands-free — the visual counterpart to [Readaloud] with no audio. Available in all three reading orientations (paginated, Vertical, Continuous), unlike [Auto-Scroll] which is scroll-only (see [ADR 0040](docs/adr/0040-cadence-covers-all-three-reading-orientations.md)). Reuses the same sentence-highlight pipeline as Readaloud: sentences are anchored to `<span id="…">` DOM nodes and rendered by the shared [HighlightRenderer] (see [ADR 0039](docs/adr/0039-sentence-playback-pipeline-shared-with-cadence.md)). Readaloud contributes its sentences from the [Readaloud Sidecar]; Cadence produces them by tokenizing the live chapter DOM at chapter-load time via the WebView's `Intl.Segmenter` (locale from EPUB `xml:lang`) — WebViews too old to expose `Intl.Segmenter` do not surface the Cadence top-bar toggle. The tick that advances the highlight is `wordCount(sentence) / wpm` for Cadence and audio-clock-driven for Readaloud. Chapter turns are text-domain `goForward` and auto-advance at chapter end (the sentence-level highlight is proof the user read through, so there is no half-viewport risk that [Auto-Scroll] guards against by stopping) — behaviour matches Readaloud's own chapter-end handling. Offered on every [Readable] book, including matched books that also have a [Readaloud]; the two features share the top bar and are **mutually exclusive at runtime** — starting one auto-stops the other, symmetrically. Speed is a per-book [Formatting Preferences] value with a global default WPM selector in Settings, plus **live speed adjustment while running via the same HUD and volume-key nudge shared with [Auto-Scroll]** (Down = slower, Up = faster). Highlight colour is picked independently from Readaloud's (`HighlightColor` palette shared, chosen values separate). The top-bar toggle can be hidden globally in Settings (fully disabling the feature); the equivalent [Auto-Scroll] toggle is independent — a small "Reader affordances" group alongside [Screen Wake Lock] and [Volume Key Navigation].

### Auto-Scroll
A reader mode in which the reading surface scrolls upward on its own at a user-set pace, so the reader can read hands-free. Available only when the [Formatting Preferences] reading orientation is **Vertical** or **Continuous** — paginated has no continuous scroll axis to creep along. Entered and exited from a top-bar toggle in the reader; while active, an in-content HUD shows the current speed and exposes finer adjustment (also nudgeable via the hardware volume keys, which give up their [Volume Key Navigation] page-turn duty while auto-scroll is running and resume it the instant it stops). At the end of a chapter in **Vertical** orientation, auto-scroll stops at the bottom of the chapter (the user pulls to advance and re-taps the toggle on the next chapter) — Vertical renders one chapter at a time, and an immediate `goForward` at `scrollY == max` would swap out the ~half-viewport of bottom text the user has not yet read. **Continuous** has no chapter boundary event, so the boundary slides past invisibly. Auto-scroll pauses on app background, screen off, manual scroll, text selection, and orientation change; it auto-resumes only after transient in-reader panels (TOC, Formatting, Search) close. Speed is a per-book [Formatting Preferences] value with a global default. Auto-scroll itself is session-level — it never starts on its own when a book opens.

### Volume Key Navigation
A global user preference (default: on) that enables page turns via the device's hardware volume buttons while reading. Applies to both EPUB and PDF readers. Volume Down advances to the next page; Volume Up goes to the previous page. When a panel (TOC or Formatting) is open, volume key presses are swallowed — no navigation occurs and the system volume UI is suppressed. While [Auto-Scroll] is active, the volume keys are repurposed to nudge auto-scroll speed (Down = slower, Up = faster), since the page-turn semantics are meaningless during continuous motion; they revert to page-turn duty the instant auto-scroll stops. Includes a secondary preference, **Invert Volume Keys** (default: off), which swaps the direction mapping so Volume Down goes to the previous page and Volume Up to the next.

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
The fourth tab in the Library Tab Bar. Displays every Library Item in the current Library as a full cover grid. Contains a persistent **Not Started filter chip** — always visible below the count header — that narrows the grid to items the user has not yet begun (see [Not Started]).

### Not Started
A Library Item the user has never begun — no ebook page opened, no audio played. Applies uniformly to all item types: ebook-only ([Readable]), audiobook-only ([Listenable]), and combined items. A combined item is Not Started only when neither the ebook nor the audio has been touched. Distinct from *In Progress* (started but unfinished) and *Completed*. Surfaced as the **Not Started** filter chip on the [All Books Tab].

### Tablet Layout
The variant of the app's UI applied when the current window's width is in Material 3's **Expanded** size class (≥ 840dp). Compact (< 600dp) and Medium (600–839dp) windows continue to use the standard phone UI — so a phone in landscape, an unfolded foldable, and a small tablet in portrait all stay on the phone layout. The Tablet Layout differs from the phone layout in three places: the Navigation Drawer becomes a Permanent Navigation Drawer; the Library Item Detail Screen splits into a two-column layout (cover and action row in a fixed left pane, metadata and description in an independently scrolling right pane); and single-column list/form screens (Settings, Downloads Screen, AddServerScreen, Library Visibility Preferences) are width-capped and centred in the content pane. The Library Tab Bar remains pinned to the bottom, cover grids continue to use adaptive cell sizing (with a larger minimum cell size on Expanded), and the reader is unchanged. The layout switches reactively on configuration change — unfolding a foldable, resizing a ChromeOS window, or entering split-screen all re-evaluate the size class.

### Permanent Navigation Drawer
The Tablet Layout variant of the Navigation Drawer. Pinned to the leading edge of the window and always visible — there is no hamburger affordance and no scrim. Contents and ordering are identical to the modal Navigation Drawer used on phones: active Server header (tappable → Server Switcher), visible Libraries, Downloads, Settings.
