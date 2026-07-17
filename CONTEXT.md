# Riffle — Domain Glossary

**Riffle** is an Android app (min API 24 / Android 7.0) for reading ebooks — reflowable EPUB and fixed-layout PDF — from user-configured **Sources**. Riffle grew up ABS-first and its early terminology (`Server`, `ABS Server`) reflected that; the domain has since been re-rooted around a general **Source** abstraction with **Service** as a peer category (see [ADR 0041](docs/adr/0041-source-and-service-abstractions-replace-server.md)). ABS remains the primary Source and the reference implementation of every optional Catalog capability; LocalFiles is the second shipping Source.

## Terms

### Source
A user-configured browsable origin of items with a library view. Types shipping today: **ABS** (Audiobookshelf) and **LocalFiles** (one or more device folders). Types the abstraction plans to accept without a shipping implementation: **OPDS**, **Calibre**, **Komga**, **Kavita**, **Gutenberg**. Every Source implements the [Catalog] interface — a small mandatory core plus opt-in [Catalog Capability] mixins that determine which features surface for that Source. Multiple Sources can be configured; the user switches between them via the [Source Switcher]. **Downloads, reading progress, and annotations are scoped per Source** — the same book on two Sources is deliberately two separate entries with two separate reading experiences (rationale: resilience via re-add; see [ADR 0041]). Configured in Settings; listed in the [Source Switcher] and [Navigation Drawer].

### Service
A sidecar that enriches items regardless of which Source they came from. Distinct from a [Source]: has no library, no browse surface, never appears in the [Source Switcher] or [Navigation Drawer], configured only in Settings. Concrete Services: **[Storyteller Service]** (produces the [Readaloud Sidecar] and audio plan for a matched item) and **annotation sync targets** (WebDAV and local-directory today; Google Drive and native provider annotation APIs are future slots — the pluggable shape is the existing `AnnotationSyncTarget`). See [ADR 0041].

### Catalog
The runtime interface every [Source] implements. Mandatory core: `listRoots / browse / search / getItem / fetchFile / connectivityCheck`. Optional [Catalog Capability] mixins provide richer surfaces. The reader never sees a Catalog directly — the Nav Drawer and library UI consume it; the reader reads whatever bytes have been Downloaded to the device.

### Catalog Capability
An opt-in mixin a [Catalog] may implement. Each mixin gates a specific UI surface for that Source:
- **`SeriesCapability`** — Series tab, series membership on the [Library Item Detail Screen].
- **`CollectionsCapability`** — Collections tab and detail.
- **`PlaylistsCapability`** — backs [To Read] and any future playlist UI.
- **`ProgressPeerCapability`** — a peer in the [Progress Sync] cycle (GET / PATCH position).
- **`ReadingSessionsCapability`** — server-side [Reading Session] records driving [Reading Statistics].
- **`StatsCapability`** — aggregated [Reading Statistics] surface.
- **`AudiobookMediaCapability`** — audiobook stream plan and chapter markers for the [Audiobook Player].
- **`OfflineBrowseCapability`** — the catalog opts into being mirrored to a `catalog_cache` for offline browse; unbounded catalogs (OPDS, Gutenberg) skip this.
UI surfaces gated on a missing capability are hidden entirely rather than degraded — a Gutenberg source has no Series tab; a LocalFiles source has no Reading Sessions.

### Source Switcher
A dropdown triggered from the [Navigation Drawer] header. Lists every configured [Source] of any type. Tapping one makes it the active Source; the Nav Drawer content re-renders with that Source's libraries. Read-only — adding and removing Sources both live in Settings. Replaces the ABS-only Server Switcher. [Service]s are never listed here.

### Storyteller Service
A user-configured [Storyteller](https://storyteller-platform.gitlab.io/storyteller) instance — a self-hosted service that aligns ebooks and audiobooks to produce EPUB 3 files with Media Overlays (SMIL). In Riffle it is a **[Service]**: it is the processor, producer, and provider of [Readaloud]s and nothing else. It contributes no library, has no browsable surface, and never appears in the [Source Switcher] or [Navigation Drawer] (see [ADR 0026], subsumed by [ADR 0041]). Its only effects in Riffle are (1) supplying the [Readaloud Sidecar] (SMIL + aligned chapter text), or the full synced bundle as a fallback ([ADR 0028]), for items on an [ABS] Source that have been matched to a Storyteller book, and (2) feeding the matcher its book metadata. Useful only alongside at least one Source that carries the ebooks it can align — in v1 that means an [ABS] Source; the taxonomy no longer bakes in "ABS only," so a future Storyteller-with-LocalFiles pairing is architecturally admissible.

Riffle only ever consumes **completed Readalouds**: Storyteller content that is not yet aligned is filtered out at the network layer (`?synced=true`). Storyteller's API exposes only `cover, title, authors` per book; that metadata is used **solely as matcher input** and is never displayed.

### Readaloud
Storyteller's term for an aligned ebook+audiobook: an EPUB 3 with Media Overlays (SMIL) that map text fragments to audio timestamps. In Riffle a Readaloud is **a capability attached to a Source item**, not a standalone item: it becomes available when a match links that Source item to a Storyteller book. When a linked Readaloud is present, the reader — still displaying the Source's own EPUB — exposes audio playback controls and visibly highlights the text segment being read (highlight anchored to sentence text from the [Readaloud Sidecar] via the cross-EPUB index). The audio is **streamed from the matched Source's audiobook** when the match is streaming-eligible (v1: only ABS with `AudiobookMediaCapability`), or played from the Storyteller synced bundle as a fallback ([ADR 0028]); the book remains fully readable without playback. The matching itself is not surfaced as a user concept.

### Readaloud Sidecar
The small alignment artifact for a [Readaloud]: the Media Overlay SMIL plus the aligned Storyteller chapter text, extracted from the Storyteller synced EPUB **without its audio** (~1 MB, versus hundreds of MB for the full bundle). Supplies the text-anchored highlight and the Storyteller side of the cross-EPUB index; a streaming-eligible Readaloud needs only the Sidecar plus the matched Source's audiobook. See [ADR 0028].

### Library
A navigation surface for a set of items within a [Source]. Every Library is backed by exactly one Source. Riffle exposes each Source's libraries 1:1: an [ABS] Source shows each of its book libraries (`mediaType=book`; podcast libraries excluded); a **[LocalFiles]** Source shows one Library per configured folder; an OPDS Source would show its top-level feed root; a Calibre Source would show each of its Calibre libraries. The user decides which Libraries in the active Source are visible in the Navigation Drawer via [Library Visibility Preferences].

### LocalFiles
The Source type backing the device's own file system as an origin. One LocalFiles Source per user, with N configured folders — each folder is a separate Library within it. Folders are picked via the Storage Access Framework. On Add-to-Library, bytes are **copied into Riffle-owned private storage** — never referenced in place — so annotation CFIs and identity are stable against external cloud-sync apps rewriting the source file (see [ADR 0041]). Implements the mandatory Catalog core; `SeriesCapability` is derived from EPUB `belongs-to-collection` metadata; `OfflineBrowseCapability` is trivially satisfied; all other capabilities are absent.

### ABS
The Source type backing an Audiobookshelf instance. Reference implementation of every optional Catalog capability except (currently) `AnnotationPeerCapability`. Retains all pre-ADR-0041 behavior — libraries, series, collections, playlists, reading sessions, stats, audiobooks — now as an implementation of the Source contract rather than a special case.

### Chitanka
The Source type backing the two public Bulgarian digital libraries [chitanka.info](https://chitanka.info) (reflowable EPUB ebooks) and [gramofonche.chitanka.info](https://gramofonche.chitanka.info) (MP3 audiobooks) — served by one Catalog exposing two [Libraries] (Books, Audiobooks). Zero-config: exactly one Chitanka Source per user, no credentials, no server URL. Both sites are anonymous read-only public catalogues, so no [Progress Sync] peer, no server bookmarks, no reading sessions, no stats. Implements the mandatory Catalog core plus `SeriesCapability` (books only, ~2,300 series enumerated via `/series/alpha/{letter}`), `AudiobookMediaCapability` (Gramofonche items — streaming direct from the site's MP3 URLs via [Audiobook Player], no chapter markers), and `OfflineBrowseCapability` (partial: TTL-cached pages only). No user-created collections; the site's two editorial reading lists (School, University) surface as extra [Category Facet] chips rather than as Collections. Ebooks fetch EPUB only (chitanka's `.fb2/.txt/.sfb/.pdf` formats are ignored); Gramofonche audiobooks with only a ZIP download and no per-track MP3s are unplayable in v1. See [ADR 0042].

### Category Facet
A **server-side** filter chip strip inside a Library screen, driven by facets the [Catalog] enumerates via `listFacets(rootId)`. Distinct from the client-side [Filtered Books Screen] (ADR 0027): client-side facets filter the already-synced-in-memory set; a Category Facet is a hint passed back to `Catalog.browse(rootId, facet=…)` and executes on the origin (e.g. Chitanka fetches `/texts/label/{genre}`). Rendered as a single-select horizontal chip strip below the search bar; "All" chip on the left. Catalogs with no server-side facets return `emptyList()` from `listFacets` and no strip renders. See [ADR 0042].

### Navigation Drawer
The primary navigation surface. Contains: the active Source name in a header (tappable → [Source Switcher] dropdown), the Source-ordered list of visible Libraries for the active Source, a Downloads entry, and a Settings entry — in that order. Replaces the standalone LibraryListScreen and ServerListScreen. On a fresh install with no Sources configured, the app opens directly to Add-Source; after the first Source is added, it navigates to the first Library in that Source's [Library Visibility Preferences] list.

### Library Visibility Preferences
A per-Source, user-managed set of hidden Libraries. Determines which Libraries appear in the Navigation Drawer for the active Source. All Libraries are visible by default; the user can hide individual Libraries. At least one Library must remain visible — the last visible Library's toggle cannot be turned off. Library order follows the Source-defined order (or the user-configured order where applicable). When opening a Source, the app navigates to the first non-hidden Library in the list.

### Library Item
An entry within a Library. Identity is `(sourceId, sourceItemId)` — the same book present in two Sources is two separate Library Items, deliberately (see [ADR 0041], [ADR 0025]). Includes metadata (title, author, cover). May or may not have an associated ebook file. May belong to a [Series], a [Collection], or neither. A Library Item with no ebook file and no audio is an [Unsupported Library Item].

### Series
A named, ordered grouping of Library Items within a Library. Defined by the [Source] when the Source's Catalog implements `SeriesCapability`; for [LocalFiles] specifically, derived from EPUB `belongs-to-collection` metadata. Sources without the capability show no Series surface.

### Collection
A user-defined, unordered grouping of Library Items within a Library. Definition varies by Source: an [ABS] Source scopes Collections to a Library and shares them across users of that Library; a [Calibre] Source uses its own custom columns; [LocalFiles] does not surface Collections. Gated on `CollectionsCapability`.

### Playlist
A per-user, per-Library ordered list of Library Items. Definition and scoping vary by Source. Gated on `PlaylistsCapability`. On [ABS] specifically, a Playlist is scoped to `(userId, libraryId)` and accepts any Library Item, including ebook-only items. [To Read] is backed by a Playlist on Sources that support it (see [ADR 0019]).

### To Read
A per-user, per-Library wishlist of Library Items the user intends to read. Gated on `PlaylistsCapability`: on an [ABS] Source, implemented as an ABS Playlist named `To Read`, scoped to `(userId, libraryId)` (find-or-created on first use — see [ADR 0019]). On a [Komga] Source, implemented as a single server-wide Komga readlist named `To Read`; the per-Library view is produced client-side by filtering the readlist's book ids to those in the requested Library. On Sources without `PlaylistsCapability`, the To Read tab is hidden entirely in v1 (a device-local shadow is a future addition, not shipping now).

Surfaced as a dedicated **To Read** tab between Home and Series when available. The tab shows a grid of the user's queued Library Items; empty state reads "Nothing in To Read".

App-managed rules (Sources with `PlaylistsCapability` only):
- **Find-or-create by name.** If the user renames the playlist on the server, the next toggle creates a new "To Read" playlist; the renamed one is left alone.
- **Per-Library, not global.** A user with multiple Libraries has one "To Read" per Library.
- **Read transitions remove from To Read.** Any transition to the Read state removes the item from "To Read". Not enforced in reverse.
- **Empty playlists disappear.** Removing the last item drops the underlying list: [ABS] deletes it server-side automatically; on [Komga], Riffle explicitly issues a DELETE to match. Either way, the next add transparently creates a fresh one.
- **Optimistic, no queueing.** Taps flip the icon immediately, fire the request, revert with a snackbar on failure. No durable mutation queue.

### Audiobook
A [Library Item] that is **listenable**: its Source-side media carries audio (`hasAudio` true) and the Source implements `AudiobookMediaCapability`. Riffle plays the audio by streaming it directly from the Source (not from a Storyteller bundle — see [Readaloud], [ADR 0023]). An Audiobook need not have an ebook: the common case is an **audiobook-only** item, and the [Audiobook Player] must never assume an ebook exists. A **combined** item — one Source item carrying both an EPUB and audio — is both readable and listenable and surfaces both a Read and a Listen affordance. Distinct from a [Readaloud], which is text+audio *aligned* (SMIL) and enriched via the Storyteller Service; an Audiobook is plain audio with no required alignment. Played in the [Audiobook Player].

### Audiobook Player
The full-screen surface that plays an [Audiobook]. Shows the square cover, title/author, and current chapter title. Its scrubber is a **seekable chapter map**: one continuous draggable track over the whole book, tick-marked at chapter boundaries and banded on the current chapter; the seek handle is a thin vertical playhead. Time is shown per chapter and whole book, mirroring the official Audiobookshelf app. The transport cluster is centered — rewind 15s, previous chapter, play/pause, next chapter, forward 30s — with the playback-speed control in a secondary utility row. Previous/next-chapter are enabled only when the audiobook has chapter markers; a chapterless single-file audiobook shows them disabled and its scrubber degrades to a plain track (no ticks/band, whole-book time only). The player is **audio-led**: the live audio position drives the book's canonical position and propagates outward through [Progress Sync] (see [ADR 0029]). Streams audio **directly from the Source** implementing `AudiobookMediaCapability` — not from a Storyteller bundle, even for a matched book (a bundle, when present, is consulted only to translate audio seconds to the text canonical). Launched by the **Listen** affordance on the [Library Item Detail Screen]. Distinct from the Readaloud player, which is a translucent bar inside the reader; the two share no code in v1.

### Readable / Listenable
The two independent capabilities a [Library Item] may have. **Readable** = has an ebook file (EPUB, PDF, or [Comic Book Archive]). **Listenable** = has audio the Source can serve as an [Audiobook]. An item may be neither, either, or both.

### Unsupported Library Item
A [Library Item] that is neither Readable nor Listenable. Displayed in the library list as dimmed; tapping it opens the [Library Item Detail Screen], which explains there is nothing to read or listen to.

### Library Item Detail Screen
A screen displaying the full metadata for a single Library Item: cover, title, author, series membership (shown as `<series name> #<sequence>` when the Source supports Series), description, published year, genres, language, and publisher. Also shows reading progress and local availability via a [Download Button]. For Readable items, contains a Read button that launches the appropriate reader (EPUB or PDF). For [Unsupported Library Item]s, the Read button is absent and the screen explains that no ebook file is available. Reachable by tapping any Library Item card in the library list, a Series detail, or a Collection detail. Back-navigating from the reader returns here before returning to the list.

Several metadata values are **tappable facets** that navigate to a [Filtered Books Screen]: each author, each genre, the published year, and the language. Series membership taps through to the existing Series detail (matched on series name only, ignoring `#<sequence>`). When the item has a [Readaloud], its badge is tappable and leads to the "has a Readaloud" Filtered Books Screen.

### Filtered Books Screen
A navigation surface listing every [Library Item] in the **current Library** that matches a single metadata **facet**: an author, a published year, a genre, a language, or "has a [Readaloud]". Reached by tapping the corresponding value on the [Library Item Detail Screen]. Library-scoped and presented as a cover grid. Facet match is computed **locally** — over the Source's catalog cache when the Source implements `OfflineBrowseCapability`, or over acquired items otherwise (see [ADR 0027]).

### Download Button
An icon-only button on the [Library Item Detail Screen] that manages the local copy of an item. Cycles through three visible states: outline arrow (not downloaded — tap to download), spinner (download in progress), filled arrow (downloaded — tap to remove). Removing a download is immediate, no Undo, no confirmation dialog; re-downloading is a deliberate user action. Applies to both EPUB and PDF items. For a [LocalFiles] Source, the file is already local — the button shows the downloaded state and a remove action.

### Cache
An implicit, evictable local copy of a Library Item's file, created automatically when the user opens a book from a Source with a remote catalog. Stored in a clearable system cache directory. Available for offline reading as long as it has not been evicted by the OS. Applies to Sources whose files arrive over the network (**[ABS]** today; future OPDS/Calibre/Komga/Kavita/Gutenberg). **Does not exist for [LocalFiles]** — its files are always local by copy-in, not evictable, and never carry a Cache tier.

### Download
A **permanent** local copy of a Library Item's file, present on the device in Riffle-owned storage as the result of an explicit user action via the [Download Button]. Never auto-cleared. Available for offline reading indefinitely until the user removes it or removes the Library Item. Distinct from [Cache]: a Cache entry becomes a Download when the user taps the Download Button; the file is promoted from the clearable directory to the permanent one.

### Downloads Screen
A dedicated screen reachable from the [Navigation Drawer], scoped to the **active Source**. Single source of truth for what is locally available for that Source and how much space it uses. Layout depends on the Source's storage model:
- **Sources with remote catalogs** (ABS today) list items in two sections: **Downloaded** (permanent, user-requested) and **Cached** (auto-created on open, may be evicted). Each row shows its file size and the appropriate indicator icon; each section header shows its total; each is individually removable (immediate, no Undo, no per-item confirmation). "Remove all" per section; bulk Downloaded removal requires a confirmation dialog, Cached does not.
- **[LocalFiles]** lists items in one section (there is no Cache tier); same per-row semantics.
There is no cache-size cap or other caching configuration — eviction of the Cached tier is OS-managed (see [ADR 0001], [ADR 0024]). A cross-Source aggregate footprint may surface in Settings as a summary; the per-Source screen is the operational surface.

### Offline Mode
The state in which the active Source cannot be reached — either because the device has no network or because the Source's backend is unreachable. Detected reactively. In this state the reader reads from [Cache] or [Download]. Reading and listening progress recorded during Offline Mode is stored locally and **durably reconciled when connectivity returns**, whether or not the book is reopened (see [ADR 0030]) — for Sources implementing `ProgressPeerCapability`. A newer server position is never overwritten. For Sources without `ProgressPeerCapability` (e.g. [LocalFiles]), all progress is local by nature and Offline Mode is not a meaningful state.

### Reading Session
A per-Source record of a single continuous reading period. Opened when the user starts reading, updated periodically, closed on reader exit or app background. Feeds server-side reading statistics. Gated on `ReadingSessionsCapability`; Sources without it (including [LocalFiles]) start no sessions.

### Reading Statistics
Aggregated reading data — time spent reading, books finished, current streaks. Gated on `StatsCapability`. Sources without it hide the Statistics surface entirely; a local aggregation is a possible future feature but is not shipping.

### Progress Sync
Reconciliation of reading position between the local canonical position and every **[Progress Peer]** for the open book. A Progress Peer is a Source (via `ProgressPeerCapability`) attached to the item — for the common case, the item's own Source. Applies to all supported formats (EPUB, PDF, [Comic Book Archive]). Comics use an integer page-index as the canonical position; on the ABS wire they piggyback on the PDF path (Readium Locator JSON) in v1 — a comic-specific fraction wire codec is on the roadmap ([ADR 0042]).

The cycle runs every ~30 s and on reader resume:
- **Unified canonical position:** the open book has a single canonical position with a single `localUpdatedAt`. Each Peer is convertible to and from the canonical position; the reconciler owns the translation.
- **Inbound (last-update-wins, single winner):** fetch all Peer positions and their `lastUpdate` timestamps. Identify the absolute newest among `{localUpdatedAt, ...peers}`. If a Peer wins, jump to its converted canonical position and set `localUpdatedAt = winner.lastUpdate`.
- **Outbound:** PATCH every Peer that is now stale relative to the canonical.
- **Per-Peer failure isolation:** GET failures skip that Peer's inbound check; PATCH failures leave that Peer stale for the next cycle.
- **No conflict prompt.**
- **Durable across Offline Mode:** reconciled when connectivity returns (see [ADR 0030]), GET-before-PATCH so a newer server position is never overwritten.
- **Zero-peer case:** for Sources without `ProgressPeerCapability` (e.g. [LocalFiles]), the cycle no-ops; progress is purely local.
- **Matched Readaloud on ABS:** the book has two Peers — ABS ebook progress and ABS audiobook progress — both first-class ([ADR 0029]). Storyteller's own position record is not a Peer; the bundle's SMIL is used only to translate audio ↔ text.
- **Ebook-only Peers (Komga, #528):** a Source that has no audiobook media implements only `ProgressPeerCapability` (the ebook half). The sweep gates the audio pass on the separate `AudiobookProgressPeerCapability` and skips it for ebook-only peers; every other invariant above holds. Komga's dialect is `PAGE_NUMBER` (an integer page as an opaque string); no CFI translator is invoked on either side.

### Formatting Preferences
User-controlled reading display settings. Scope varies by format:
- **EPUB:** font size, theme (Light / Dark / Sepia / [Auto Theme]), font family (system fonts + Literata, Merriweather, OpenDyslexic), justify text (on/off, default off), line spacing, margins, reading orientation (paginated / vertical / continuous).
- **PDF:** theme (as colour filter, same value set including [Auto Theme]), zoom persistence. PDF is paged-only today.
- **[Comic Book Archive]:** none in v1. Fit Whole, paginated, LTR are all hard-coded; the reader shows no Formatting Preferences button (see [ADR 0042]).

Formatting Preferences are per-device and Source-agnostic.

### Auto Theme
A theme value that sits alongside Light, Dark, DarkDim, and Sepia in the [Formatting Preferences] theme picker. Selected like any other theme — globally as the default or per-book as an override — but resolves at render-time according to the [Theme Schedule]. The chip in the formatting panel uses a split day/night swatch. A book pinned to a concrete theme is unaffected; a book pinned to Auto follows the schedule.

### Theme Schedule
A global, user-configured pair of clock times and theme picks that drive [Auto Theme]. Four fields: day-start, night-start, day-theme, night-theme. Theme picks restricted to the four concrete themes. Interpreted on the device's local clock as two arcs on a 24-hour circle; the night arc may cross midnight. Defaults: 07:00, 21:00, Light, Dark. Applies uniformly to EPUB and PDF. Boundary crossings during an open reading session repaint live. Editable only on the full-screen Settings panel. See [ADR 0022].

### EPUB CFI
An EPUB Canonical Fragment Identifier — `epubcfi(/6/N!<docPath>)` pinpointing a location within an EPUB chapter. Two dialects: Readium (XPath-style) and epub.js (character-count, used by ABS). See [ADR 0013].

### EPUB CFI Translator
The layer (`EpubCfiTranslator`) that converts between Readium's native position and the character-count CFI used by ABS. All `ebookLocation` values crossing the Riffle↔ABS API boundary pass through it. Only relevant for Sources whose `ProgressPeerCapability` stores the epub.js dialect (i.e. ABS); [LocalFiles] uses Readium's native CFI end-to-end. See [ADR 0013].

### Annotation
Umbrella term for the three user-authored marks attached to a Library Item: [Highlight], [Note], and [Bookmark]. All share one storage model and one [Annotation Sync] format. Each carries a stable client-generated ID, creation and last-modified timestamps, and a device-origin tag, and anchors to an EPUB CFI. Every Annotation additionally stores the surrounding text snippet and chapter href as a human-readable fallback.

Annotations are keyed by the Library Item's `(sourceId, sourceItemId)`. Since every book is read from the Source's own EPUB, Annotations are available during all reading, with or without a Readaloud. Not available on PDF or [Comic Book Archive] in v1 — the anchor is EPUB CFI ([ADR 0024]); a page-anchored variant is on the roadmap alongside PDF annotations.

### Annotation Sync
The mechanism by which [Annotation]s roam between devices. The local Room store is always the primary, queryable store; Sync is an optional [Service] layer, reached through a single `AnnotationSyncTarget` abstraction (`list / read / write-own-file`) so the backing store is swappable. On-the-wire format: **W3C Web Annotation Data Model** (JSON-LD), with a `riffle:` extension namespace carrying merge-critical fields (`device`, `updatedAt`, `deleted`). Two target *kinds* are anticipated: **blob-store** (a folder of one file per device per book, merged client-side by per-record last-write-wins) and **record-store** (per-record CRUD with server-side merge). **v1 ships local-only** (with WebDAV target scaffolded); the schema is sync-ready so enabling a target later is additive.

### Highlight
A marked **text range** in an EPUB, stored with a colour and an optional [Note]. Colour is a token from a small fixed palette (yellow — default — green, blue, pink); the reader's theme system owns the light/dark RGB mapping. Anchored to a **CFI range** (start + end). An [Annotation].

### Note
A user-written text comment on a [Highlight], annotating that range. In v1 not a standalone entity — an optional field on a Highlight. An [Annotation].

### Bookmark
A saved reading location with no text selection — a pure location marker. Anchored to a **CFI point** at the top-of-viewport text of the current page (the reader's `currentLocator`). An [Annotation].

### Annotations View
A tab in the [Library Tab Bar], positioned between To Read and Series, surfacing every Library Item in the currently viewed Library that has at least one [Highlight]. Library-scoped: a book only appears in the Annotations tab of the Library its `library_items` row belongs to — a highlight whose book row has no match in the current Library (or no `library_items` row at all) does not surface here. Backed by the local [Annotation] store, so it works offline. Presented as a cover grid identical in shape to the [Home Tab] / [All Books Tab] — each card shows the cover, title, author, and a highlight-count badge. Sorted by most-recently-annotated (max `updatedAt` across the book's highlights). Empty state reads "No highlights yet. Long-press text while reading to highlight it." No tab badge count in v1.

Opening a book from the Annotations View does not open the ABS EPUB — it opens the **elided reader**: a synthesised in-memory publication whose spine holds only chapters that contain highlights, and whose rendered text is the highlights themselves in CFI order with attached [Note]s inline. Chapter headings are the only structural scaffolding; nothing else from the source EPUB appears. Chapter titles resolve from the cached [Table of Contents (TOC)] when available, falling back to the href basename. Highlight colour is preserved: each rendered snippet is painted with its stored [HighlightColor]'s palette background via inline CSS. All standard reader affordances still apply — [Formatting Preferences] (shared with the source book's per-book prefs, so font/theme/orientation follow), [Table of Contents (TOC)] (flat, chapters-with-highlights only), [Book Search] (across highlight text + notes), [Cadence], [Auto-Scroll] (Vertical/Continuous only, same rule as the source reader), [Immersive Mode], [Volume Key Navigation], [Screen Wake Lock] — with three exceptions: no [Progress Sync], no [Reading Session], and no new-highlight creation gestures (there is no unhighlighted text to select). [Readaloud] and the [Chapter Navigation Rail] are unavailable. Resume position is local-only and per-device (not synced).

Tapping a rendered highlight opens the same action sheet as in the source reader (recolour, edit/add [Note], delete, "Open in book"). All edits go through the [Annotation Sync] pipeline — the row modified is the same row read by the source reader. "Open in book" opens the source EPUB at the highlight's CFI, fetching+caching from ABS if online; when the source is unavailable (offline and uncached, or removed from ABS) it shows a "Book not available" message. Exact-CFI duplicate highlights are prevented at write time in the source reader (see `highlightOverlapsAtSamePosition`), so the elided view has no render-time dedup or disambiguation; the rare cross-device duplicate is rendered in CFI order and accepted as a cosmetic doubling. PDF books are out of v1 scope — the list query returns [Highlight]s only, and [Annotation]s are anchored on EPUB CFI (see [ADR 0024]), so PDF-only books do not appear. The tab name and the query are format-neutral so a future PDF annotation type slots in without rework. See [ADR 0041](docs/adr/0041-annotations-view-elided-reader-over-local-store.md).

### Table of Contents (TOC)
The navigable chapter and subchapter structure of a Library Item, derived from the EPUB or PDF's own structure.

### Chapter Navigation Rail
A thin UI element fixed at the bottom of the reader screen. Visualises the current chapter's subchapter breakdown and the user's position. Tapping a segment jumps to that subchapter.

### Screen Wake Lock
A global user preference (default: on) that prevents the device screen from sleeping while a book is open. Applies to EPUB and PDF. Always held while [Auto-Scroll] is running, regardless of the preference, and released the moment auto-scroll stops.

### Cadence
A reader mode in which the highlighted sentence advances on its own at a user-set pace (words-per-minute) — the visual counterpart to [Readaloud] with no audio. Available in all three reading orientations (see [ADR 0040]). Reuses the sentence-highlight pipeline as Readaloud ([ADR 0039]): sentences anchored to `<span id="…">` DOM nodes rendered by the shared HighlightRenderer. Readaloud contributes sentences from the [Readaloud Sidecar]; Cadence produces them by tokenizing the live chapter DOM at chapter-load time via `Intl.Segmenter` (WebViews without it hide the top-bar toggle). The tick that advances the highlight is `wordCount(sentence) / wpm` for Cadence and audio-clock-driven for Readaloud. Chapter turns are text-domain `goForward` and auto-advance at chapter end. Offered on every Readable book; Cadence and Readaloud share the top bar and are mutually exclusive at runtime. Speed is a per-book [Formatting Preferences] value with a global default WPM in Settings, plus live speed adjustment via a HUD and volume-key nudge shared with [Auto-Scroll]. Highlight colour is picked independently. Source-agnostic.

### Auto-Scroll
A reader mode in which the reading surface scrolls upward on its own at a user-set pace. Available only when the [Formatting Preferences] reading orientation is Vertical or Continuous. Entered/exited from a top-bar toggle; while active, an in-content HUD shows the current speed. At the end of a chapter in Vertical orientation, auto-scroll stops at the bottom of the chapter; Continuous slides past invisibly. Pauses on app background, screen off, manual scroll, text selection, and orientation change; auto-resumes only after transient in-reader panels close. Speed is per-book with a global default. Source-agnostic.

### Volume Key Navigation
A global user preference (default: on) that enables page turns via the device's hardware volume buttons. Applies to EPUB and PDF. Volume Down advances; Volume Up goes back. Swallowed when a panel is open. Repurposed to speed-nudge during Auto-Scroll and Cadence. Includes an **Invert Volume Keys** preference (default: off).

### Immersive Mode
A reader state in which the TopAppBar and Android's system bars are hidden. Toggled by tapping the reading content. The reader always opens immersive. Not persisted across sessions.

### Book Search
An in-EPUB text search available while reading. Activated via a search icon in the reader's TopAppBar. Searches the full publication via Readium's `SearchService`; results highlighted directly in the reading content. Applies to EPUB only — not available on PDF or [Comic Book Archive] (both are page-image formats with no queryable text stream). Source-agnostic.

### Supported Formats
EPUB (reflowable), PDF (fixed-layout), and [Comic Book Archive] (page-image). EPUB and PDF are rendered via the Readium Kotlin SDK (EPUB navigator + Pdfium adapter); comics use a purpose-built page-image renderer over the archive's entries.

### Comic Book Archive
A page-image ebook format — a ZIP (`.cbz`) of images, one image per page, in filename-sorted order. Ships as the third [Readable] format in v1. The `.cbr` (RAR) sibling is on the roadmap; a file mislabeled `.cbz` that is actually a RAR is caught at open time via magic-byte sniff and surfaces a clean "not supported" message. Rendered by the **Comic Reader** — a purpose-built Compose surface, distinct from EPUB's Readium navigator and PDF's Pdfium fragment. Reading orientation is paginated only in v1 (no webtoon, no two-page spread); default page fit is Fit Whole; reading direction is left-to-right (no RTL/manga toggle). Navigation clones CDisplayEx's default interaction model: horizontal tap-thirds (previous / immersive-toggle / next), horizontal swipe, pinch-to-zoom + pan + double-tap-to-reset on each page, plus a bottom page scrubber that appears with the top bar. [Panel View] is available as an in-reader toggle. [Volume Key Navigation], [Screen Wake Lock], [Immersive Mode], and [Progress Sync] apply unchanged; [Book Search], [Table of Contents (TOC)], [Chapter Navigation Rail], [Formatting Preferences], [Readaloud], [Cadence], [Auto-Scroll], and [Annotation]s are unavailable in v1 (see [ADR 0042]). User-facing copy calls it **Comic**.

### Panel View
An opt-in [Comic Reader] mode in which the camera frames one **panel** at a time instead of the whole page, in reading order. Modeled on Kindle's Panel View / Komga's Guided View. Toggled from an icon in the reader's TopAppBar; state is remembered **per-book, per-device** (never synced). Panels are discovered client-side: if the archive supplies an `ACBF` sidecar with `<frame>` regions they are used verbatim (standard `ComicInfo.xml` carries no panel-region field); otherwise a lightweight on-device detector runs lazily on first entry to a page (downscale → threshold → flood-fill the connected gutter → panels are the trapped connected components) and prefetches the next two pages, caching results to disk keyed by `(archiveHash, pageIndex, imageSize)`. Reading order within a page is source-provided when available, else row-band inferred (cluster rows by y-overlap, sort rows top-to-bottom, panels within a row left-to-right; RTL manga deferred). If detection produces an implausible result (0 panels or 1 whole-page panel), Panel View silently falls back to Fit Whole for that page. **Interaction**: tap-right / swipe-left / Vol-Down = next panel; tap-left / swipe-right / Vol-Up = previous panel; tap-middle = toggle [Immersive Mode]; **long-press = whole-page peek** with a "skip guided panels on this page" escape hatch; pinch-zoom / pan / double-tap-reset are **disabled** while Panel View is on. **Transitions**: animated Matrix interpolation (~250ms ease-in-out) between panels within a page; across a page boundary, a three-stage chain (zoom-out to fit-whole, page-slide, zoom-in to first panel of the new page); collapses to instant cuts when the OS Reduce Motion setting is on; taps mid-animation cancel and restart from the current visual state. **Canonical position stays integer page index** — panel index is per-device transient (like pan/zoom, see [ADR 0042]), persisted locally so same-device resume lands on the last panel, but discarded whenever [Progress Sync] pulls a newer page from a peer. Bottom scrubber remains page-granularity. Applies to CBZ in v1; CBR when it ships; PDF is out of scope. See ADR 0043.

### Library Tab Bar
The bottom navigation surface within a Library screen. Contains up to six icon-only tabs — Home, To Read, Annotations, Series, Collections, and All Books — scoped to the currently viewed Library. Individual tabs are hidden if the active Source lacks the underlying capability (To Read without `PlaylistsCapability`, Series without `SeriesCapability`, Collections without `CollectionsCapability`). Replaces the Plex-style single scrollable feed as the primary way to browse a Library's content. The Navigation Drawer remains the navigation surface for switching Libraries, accessing Downloads, and Settings.

### Home Tab
The first tab in the Library Tab Bar. Displays In Progress and Completed sections with horizontal cover grids and "See All" links.

### Series Tab
The second tab in the Library Tab Bar. Displays all Series in the current Library as a full cover grid. Hidden when the Source lacks `SeriesCapability`.

### Collections Tab
The third tab in the Library Tab Bar. Displays all Collections as a full cover grid. Hidden when the Source lacks `CollectionsCapability`.

### All Books Tab
The fourth tab in the Library Tab Bar. Displays every Library Item in the current Library as a full cover grid. Contains a persistent **Not Started filter chip** below the count header.

### Not Started
A Library Item the user has never begun — no ebook page opened, no audio played. Applies uniformly to all item types. A combined item is Not Started only when neither the ebook nor the audio has been touched. Distinct from *In Progress* and *Completed*.

### Tablet Layout
The UI variant applied when the current window's width is in Material 3's **Expanded** size class (≥ 840dp). Nav Drawer becomes a Permanent Navigation Drawer; Library Item Detail Screen splits into a two-column layout; single-column list/form screens are width-capped and centred. Library Tab Bar remains pinned to the bottom.

### Permanent Navigation Drawer
The Tablet Layout variant of the Navigation Drawer. Pinned to the leading edge and always visible. Contents and ordering identical to the modal drawer used on phones.
