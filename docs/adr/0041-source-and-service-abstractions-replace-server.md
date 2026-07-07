# Source and Service abstractions replace Server

Riffle was built as a client for a self-hosted Audiobookshelf instance and its domain reflects that: `Server` = ABS Server; `Library` = an ABS library; `library_items` mirrors the ABS catalog and doubles as the reader's library; Storyteller was retrofitted as an odd second kind of Server ([ADR 0020], [ADR 0026]) because there was no other category available. To make Riffle usable against other backends (OPDS, Calibre, Komga, Kavita, Gutenberg, plain local files) — even if we only ship one alternative in the near term — we replace `Server` with two peer root concepts:

- **Source.** A browsable origin of items with a library view. Concrete types: **ABS**, **LocalFiles**, and slots for **OPDS / Calibre / Komga / Kavita / Gutenberg**. Every Source implements a **Catalog** interface: a small mandatory core (`listRoots / browse / search / getItem / fetchFile / connectivityCheck`) plus opt-in capability mixins (`SeriesCapability`, `CollectionsCapability`, `PlaylistsCapability`, `ProgressPeerCapability`, `ReadingSessionsCapability`, `StatsCapability`, `AudiobookMediaCapability`, `OfflineBrowseCapability`). Features surface or hide per source based on the mixins the source's Catalog implements — a Gutenberg source shows no Series tab; a LocalFiles source has no Reading Sessions; an ABS source keeps everything.
- **Service.** A sidecar that enriches items regardless of which Source they came from. Never appears in the Source Switcher, never has a library view, configured only in Settings. Concrete types: **Storyteller** (produces the Readaloud Sidecar + audio plan for an item), **WebDAV / local-directory annotation sync targets** (existing `AnnotationSyncTarget`). Future targets (Google Drive, native ABS annotations if that ships) slot into the same category.

## Corollaries

- **The reader is a per-Source world.** There is no merged cross-source library. Each Source has its own library view, its own downloads, its own progress, its own annotations. The user switches between Sources via a **Source Switcher** in the Nav Drawer header (replacing today's Server Switcher). Rationale: **resilience via re-add** — if a device is lost, the user re-configures their Sources and each Source's library restores itself (ABS from the server, LocalFiles from the folder scan, etc.). A single merged library would force a full rebuild.
- **Same book on two Sources is two entries, deliberately.** Identity is `(sourceId, sourceItemId)`. If Moby Dick lives on both an ABS server and a LocalFiles folder, that's two library rows, two annotation streams, two progress lines. Cross-Source unification is out of scope; a Service (e.g. a WebDAV annotation target) is the intended path to merge state across devices for a single Source.
- **The `library_items` table graduates.** Today it doubles as ABS-catalog mirror and reader's library, and its identity — item id alone — collides across Servers ([ADR 0025]). Under this ADR the mirror becomes a per-Source `catalog_cache` (opt-in via `OfflineBrowseCapability`), and every table that references an item keys on `(sourceId, sourceItemId)`. This ships the fix that [ADR 0025] tabled as future work.
- **Storage tiers stay Source-shaped.** Sources with remote catalogs (ABS today, OPDS/Kavita/Komga/Calibre later) retain the two-tier Cache / Download split from [ADR 0001] — Cache is implicit-on-open and evictable, Download is explicit and permanent. [LocalFiles] has one tier (permanent, copy-in) because its files are always local; the Cache concept does not apply to it. The Downloads Screen renders one or two sections per active Source accordingly.
- **Storyteller loses its "Server" clothing.** It has no library, no browse, no Source Switcher presence — it's a Service. The layered justifications in [ADR 0020] and [ADR 0026] collapse into "Storyteller is a Service that provides `ReadaloudProvider`."

## Scope of the first change

Ship the abstraction, one new Source implementation (**LocalFiles**), and the ABS refactor behind the Catalog interface. Do not implement OPDS / Calibre / Komga / Kavita / Gutenberg — validate the interface against their capability shapes but leave them for later. LocalFiles is the second implementation because it is the smallest independent proof that the abstraction holds. Existing user data migrates via a mechanical column rename (`servers` → `sources` with a `type` column; `serverId` → `sourceId`); no semantic data change.

## Amendments to existing ADRs

- **[ADR 0007]** (Nav Drawer): Source Switcher header, per-Source Libraries.
- **[ADR 0011]** (Unified local store): Source-scoped tiers; one storage tier; Cache tier removed.
- **[ADR 0019]** (To Read as Playlist): gated on `PlaylistsCapability`; hidden for Sources without.
- **[ADR 0020]** (Storyteller as peer server): superseded — Storyteller is a Service.
- **[ADR 0025]** (Key local stores by Server and Item): superseded — shipping design, renamed to Source.
- **[ADR 0026]** (Storyteller as Settings-only backend): superseded — Storyteller is a Service (Settings-only follows from the category).
- **[ADR 0027]** (Client-side facet filtering): amended — over the active Source's catalog cache when present, or over acquired items otherwise.
- **[ADR 0029]** (Audiobook direct-ABS streaming): amended — any Source implementing `AudiobookMediaCapability`; ABS is the reference implementation.
