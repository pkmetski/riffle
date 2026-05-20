# Riffle — Domain Glossary

**Riffle** is an Android app (min API 24 / Android 7.0) for reading ebooks from a self-hosted Audiobookshelf server.

## Terms

### Server
A user-configured Audiobookshelf server instance (URL + credentials). Multiple Servers can be added; the user switches between them manually. All local data (Cache, Downloads, Annotations) is scoped to a Server.

### Library
A top-level collection on the connected ABS server. Riffle only surfaces Libraries whose `mediaType` is `book` — podcast, audiobook-only, and other non-ebook libraries are hidden from the user entirely.

### Series
A named, ordered grouping of Library Items within a Library. Defined on the ABS server (e.g. "The Stormlight Archive").

### Collection
A user-defined, unordered grouping of Library Items within a Library. Distinct from Series — not necessarily sequential.

### Library Item
A book entry within a Library. Includes metadata (title, author, cover) and a reference to the EPUB file hosted on the server. May belong to a Series, a Collection, or neither.

### Cache
A local copy of a Library Item's EPUB file that the app creates automatically when the user opens a book. Stored in a clearable system cache directory. Available for offline reading as long as it has not been evicted.

### Download
A local copy of a Library Item's EPUB file that the user explicitly requests. Stored in a permanent directory that is never auto-cleared. Available for offline reading indefinitely.

### Offline Mode
The state in which the app cannot reach the ABS server. In this state, the app reads from Cache or Download. Reading progress recorded during Offline Mode is queued for Progress Sync.

### Reading Session
A server-side record of a single continuous reading period. Opened via the ABS API when the user starts reading, updated periodically, and closed when the user leaves the reader or the app backgrounds. Feeds server-side reading statistics (time read, pages per day, streaks).

### Reading Statistics
Aggregated reading data fetched from the ABS server — time spent reading, books finished, current streaks. Populated by Reading Sessions pushed from the app.

### Progress Sync
Bidirectional reconciliation of reading position between the app and the ABS server.

- **Outbound:** local progress is pushed to the server periodically (every ~30 seconds) and on reader close.
- **Inbound:** the server's latest position is fetched when the app resumes from background or sleep.
- **Conflict resolution:** when the local position and server position differ meaningfully (i.e. neither is trivially ahead due to a single sync delay), the user is prompted to choose which position to continue from.

### Formatting Preferences
User-controlled reading display settings. Scope varies by format:
- **EPUB:** font size, theme (Light / Dark / Sepia), font family (system fonts + Literata, Merriweather, OpenDyslexic), line spacing, margins, reading orientation (paginated / continuous scroll).
- **PDF:** theme (as colour filter), scroll direction (paged / continuous), zoom persistence.

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
A reader-active setting that prevents the device screen from sleeping while a book is open.

### Supported Formats
EPUB (reflowable) and PDF (fixed-layout). Rendered via the Readium Kotlin SDK (EPUB navigator + Pdfium adapter).
