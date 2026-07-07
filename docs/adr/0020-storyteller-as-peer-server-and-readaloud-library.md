# ADR 0020 — Storyteller as a peer Server type; Readaloud Library as its single navigation surface

> **Further superseded by [ADR 0041](0041-source-and-service-abstractions-replace-server.md)** — Storyteller is a **Service** (not a Server or Source of any kind). The "peer Server" framing this ADR walked back is now moot: the taxonomy has no such category. Kept for history.

**Status:** Superseded by [ADR 0026](0026-storyteller-as-settings-only-readaloud-backend.md)

> **Superseded.** Storyteller is no longer a browsable peer Server and contributes no Readaloud Library. It is a Settings-only Readaloud backend; the Readaloud capability surfaces only on matched ABS Library Items, and the ABS→Storyteller metadata-borrow rule is dropped. Storyteller-only deployments are no longer supported. The matching mechanism ([ADR 0021](0021-storyteller-abs-matching-with-review-queue.md)) and three-peer sync ([ADR 0019](0019-three-peer-unified-canonical-progress-sync.md)) survive. The historical record below is retained for context only.

## Context

Storyteller is a self-hosted platform that aligns ebooks and audiobooks to produce EPUB 3 files with Media Overlays (SMIL). In Storyteller's own terminology, an aligned book is a **readaloud**. The integration goal is that a user with a Storyteller server gets readaloud playback on their books, with progress reconciled across Audiobookshelf and Storyteller.

**Scope: Storyteller is consumed only for its readaloud output.** Riffle does not ingest plain ebooks or plain audiobooks from Storyteller; Audiobookshelf is the canonical source for those formats. Storyteller content that is not yet a completed readaloud (uploaded source files, in-progress alignment, failed alignment) is filtered out and never surfaces in the Readaloud Library. The Storyteller integration buys Riffle exactly one capability ABS lacks: synchronised audio + text playback. Anything outside that capability stays on the ABS side.

Two structural shapes were available:

- **Sidecar enrichment** — Storyteller attached to a single ABS Server in Settings, surfacing readaloud capabilities on matched ABS Library Items but contributing no Library Items of its own.
- **Peer Server type** — Storyteller treated as a first-class Server alongside ABS, contributing its own Library Items and metadata.

Storyteller exposes username+password → bearer token auth at `POST /api/token` (multipart form: `username`, `password` → `{access_token, token_type, expires_in}`), close in shape to the existing ABS auth flow Riffle already implements. Its REST surface is intentionally minimal — confirmed by reading the official Storyteller mobile app's `apiClient.ts`:

- `GET /api/books?synced=true` — list completed readalouds.
- `GET /api/books/{id}` — book detail.
- `GET /api/books/{id}/cover` — cover image.
- `GET /api/books/{id}/synced` — aligned EPUB bundle (multi-hundred-MB).
- `GET /api/books/{id}/positions` / `POST` — read position.
- `GET /api/validate` — token validation.

The per-book payload is `{ id: number, title, authors[{name, file_as, role}], processing_status }`. There is **no Library concept**, **no Series endpoint**, **no Collections endpoint**, **no Tags endpoint**, and **no extended metadata** (description, publisher, year, genres) exposed over the API. Storyteller's web UI surfaces Collections and Series via server-side rendering against its private database; mobile/native clients only see the flat book list. Extended metadata, if needed, is reachable only by downloading and parsing the EPUB OPF — out of scope for the initial integration.

A user may run Storyteller without ABS, run both, or run multiple of either type. The "behaves just like ABS books" requirement implies a uniform reading experience regardless of which Server a book came from.

## Decision

**Storyteller is a peer Server type.** The existing `Server` concept generalises: a Server is either an ABS Server or a Storyteller Server. Both types are added via the same Add-Server flow (URL + credentials → bearer token in Keystore); both appear in the Server Switcher; both contribute Libraries to the Navigation Drawer. The reading experience — formatting, themes, navigation, sync mechanics — is identical across types.

A Storyteller Server contributes **exactly one synthetic Library: the Readaloud Library**, holding every completed readaloud on that Storyteller instance (the server-side `?synced=true` filter excludes in-progress, unaligned, and failed-alignment books). Because Storyteller's REST surface does not expose Series, Collections, or Tags, the Readaloud Library renders with **only the All Books tab** of the standard Library Tab Bar — Home, Series, and Collections tabs are hidden for Storyteller Servers. Multiple Storyteller Servers each contribute their own Readaloud Library, disambiguated by Server name.

**Matched books appear on both sides.** A Confirmed-matched book lives in its ABS Library *and* in the Readaloud Library. The reader behaviour and capabilities differ by entry point:

- **ABS-side Read** opens the ABS-served EPUB. No readaloud controls. Fast — preserves today's ABS reading experience without modification.
- **Readaloud-side Read** opens the Storyteller-served EPUB (with SMIL bindings). The reader exposes readaloud controls — a headphones icon in the TopAppBar opens a bottom mini-player.

The matching link's purpose is **cross-Server progress sync** (per [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md)) and **display-metadata sourcing**, not the collapse of entry points.

**Metadata sourcing rule:**

- **Storyteller-native display fields are limited to `cover, title, authors`** — the only metadata the Storyteller API exposes. For an Unmatched Storyteller book, that is everything the Readaloud Library can show.
- **For a Confirmed-matched book**, display fields (cover, title, author, description, published year, publisher, genres) come from the linked ABS Library Item wherever the book is displayed, including in the Readaloud Library. This is the only path by which a Storyteller book in Riffle shows description/year/publisher/genres.
- **Taxonomy is not a Readaloud Library concern.** Series and Collections live in ABS Libraries; the Readaloud Library has no Series or Collections tabs because Storyteller exposes neither.

## Alternatives considered

**Sidecar enrichment attached to an ABS Server.** Rejected because it prevents Storyteller-only deployments (Storyteller user without ABS) and forces orphan Storyteller books into a synthetic Library inside an ABS Server's namespace, which awkwardly conflates two backends' identities.

**Sidecar enrichment with no UI surface for Storyteller-only books.** Rejected because it makes the unmatched-Storyteller-book case invisible and unreadable.

**Per-Library multi-Provider attachment** (a Riffle Library backed by 1+ Providers, mixed merging). Rejected as too configurable for v1: forces the user to think about provider attachment, breaks the implicit "Libraries come from the Server" model, and only adds value in the niche where a user wants to merge Storyteller orphans into a specific ABS Library.

**Single merged view across Servers** (matched books deduplicated to a single visible row). Rejected because it requires the matching layer to be a hard correctness dependency of the *browsing* experience — a wrong match becomes a confusing display merge with no visible cause. Showing the book in both Libraries makes mismatches visible and survivable, and Library Visibility Preferences let the user hide one side if they prefer.

**Storyteller's flat book list rendered without the Library Tab Bar.** Originally rejected on the assumption that Storyteller exposed Series, Collections, and Tags as first-class API entities. **Re-examined and adopted in part** after probing the live server and the official Storyteller mobile app: those entities are server-rendered only in the web UI and not available to API clients. The Readaloud Library therefore renders the standard Library Tab Bar with only the All Books tab visible — Home / Series / Collections are hidden — until Storyteller adds taxonomy endpoints upstream.

**Parse the EPUB OPF on first detail open to backfill description / year / publisher / genres.** Rejected for the initial integration. The aligned EPUB bundle is multi-hundred-MB; touching it just to populate a detail screen is disproportionate. For Confirmed matches the ABS-borrowed metadata covers the same fields. A follow-up may revisit this if a low-cost OPF-only fetch endpoint lands on Storyteller, or if matching coverage proves insufficient.

**Surface Storyteller's bare ebooks and bare audiobooks too** (treat Storyteller as a general-purpose content provider). Rejected: ABS is Riffle's canonical source for plain ebooks and plain audiobooks, and Storyteller's plain-content surfaces are a strictly weaker version of that role (no per-chapter audio streaming, no native podcast/audiobook session model, no audiobook progress endpoint that ABS clients consume). Storyteller earns its slot in Riffle precisely by being the only path to readalouds; expanding its scope would duplicate ABS responsibilities without benefit and add UX edge cases (a Storyteller-only "plain ebook" that has no readaloud — what would distinguish it from an ABS plain ebook?).

## Consequences

- The `Server` domain term and the `Library` domain term are both generalised (already reflected in `CONTEXT.md`). All per-Server scoping that exists today (Cache, Downloads, Library Visibility Preferences, progress) applies to Storyteller Servers unchanged.
- The Storyteller API client filters book listings to **completed readalouds only** via `GET /api/books?synced=true`. Non-readaloud Storyteller content never enters Riffle's data layer; downstream code can assume every Storyteller-sourced Library Item is a readaloud.
- The Readaloud Library uses the same Library Tab Bar component as ABS Libraries but renders only the All Books tab. Home / Series / Collections tabs are hidden for Storyteller-backed Libraries because the API exposes no corresponding data. No Storyteller-only navigation chrome is introduced.
- Tags are not surfaced. There is no Storyteller Tags endpoint to back them.
- A Storyteller-only deployment is fully supported: a user with no ABS Server sees one Storyteller Server in the drawer, one Readaloud Library, and the full reading + readaloud experience.
- A user with both backends sees matched books in two Libraries. Library Visibility Preferences let them hide either side if they prefer one canonical entry point.
- The ABS-side Read button for a matched book is **deliberately unchanged in its reader UI** — no readaloud controls, no headphones icon, no audio playback. The audiobook bundle (potentially multi-GB) is never fetched implicitly from ABS browsing; only the explicit Readaloud-side "Download readaloud audio" action triggers it.
- **However, three-peer Progress Sync runs from either side** of a matched book once its small sync prerequisites are cached. Confirmed-match creation (ADR 0021) eagerly fetches the Storyteller EPUB bundle (few MB) and builds the cross-EPUB index in the background. Until those prerequisites land, the matched-book cycle gracefully degrades to single-peer for whichever side is open (see ADR 0019). This split — small sync prerequisites fetched silently, large audio bundle fetched only on explicit opt-in — preserves "no surprise multi-GB downloads" while still satisfying the requirement that reading from any side syncs to all three peers.
- Multiple Storyteller Servers: each contributes its own Readaloud Library; matching scope is global across every configured ABS Server, not per pair.
- Storyteller's bundle-based audio delivery (no per-chapter HTTP streaming via the mobile API path) constrains the Download model: audio is fetched as one Readium-audiobook archive per book, downloaded explicitly via a separate "Download readaloud audio" action on the Library Item Detail Screen. The EPUB bundle continues to follow the existing cache-on-open + explicit-download tiers.
- This ADR does not specify the matching algorithm or its review UI — see [ADR 0021](0021-storyteller-abs-matching-with-review-queue.md).

### Add-Server flow

The Add-Server form opens with an explicit Server-type picker (segmented control: **Audiobookshelf** / **Storyteller**) above the URL and credentials fields. The two backends use the same shape (URL + username + password → bearer token in Keystore), but the picker determines which auth endpoint and request shape are used (ABS: `POST /login` with JSON body `{username, password}`; Storyteller: `POST /api/token` with multipart form `username`, `password`) and which type-specific help text is shown (e.g. ABS's "Allow insecure HTTP" warning). Auto-detection from the URL was rejected to avoid baking probe heuristics into onboarding for marginal UX gain.

### Server removal cascade

Removing any Server clears all ReadaloudLinks that involve it, in either direction:

- Removing a **Storyteller Server** removes the Readaloud Library, all its Library Items' cached EPUB bundles and downloaded audio bundles, and every ReadaloudLink whose Storyteller side is on this Server. Matched ABS Library Items on the surviving ABS Servers revert to no-readaloud state.
- Removing an **ABS Server** removes its Libraries and per-Server local data as today, and additionally clears every ReadaloudLink whose ABS side is on this Server. The Storyteller books on the surviving Storyteller Server that previously had Confirmed matches revert to Unmatched and their display metadata reverts from ABS-borrowed to Storyteller-native.

The existing removal confirmation dialog gains a count line when applicable (e.g. "Removing this server will also clear 12 readaloud links"). There is no separate undo path — removal is final, consistent with the existing per-Server-removal contract.

### Library Item Detail Screen — four cases

The Detail Screen behaviour depends on which side it's reached from and whether a Confirmed ReadaloudLink exists. All four cases share Mark-Read and To Read affordances as today.

- **Case 1 — ABS-side, no match.** Today's behaviour unchanged. Read button opens the ABS EPUB; Download Button manages the EPUB cache/download; no readaloud affordances.
- **Case 2 — ABS-side, Confirmed match.** Same as Case 1, plus a footer line below the action row: "Readaloud available — open from *[Readaloud Library name]*" that tap-navigates to the matched Library Item on the Readaloud side. Reading progress shown is the unified canonical position from [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md) — reading from the ABS side participates in three-peer sync once prerequisites are cached. **Library card badge:** a small readaloud glyph also appears on the ABS-side Library card in the grid, signalling availability before the user opens the Detail Screen.
- **Case 3 — Readaloud-side, no match (Storyteller-only or Unmatched).** Display metadata is Storyteller-native — limited to cover, title, and authors (the API surfaces nothing else). Read button opens the Storyteller EPUB with the headphones icon ready in the TopAppBar. Two independent download actions: **Download** (EPUB bundle) and **Download readaloud audio** (audiobook bundle). Footer: "Not linked to an ABS book — *Pair manually*" (the unmatched-pairing entry point from [ADR 0021](0021-storyteller-abs-matching-with-review-queue.md)). When neither bundle is present and the device is offline, the Read button is disabled with a "Connect to download book" tooltip.
- **Case 4 — Readaloud-side, Confirmed match.** Same as Case 3, with display metadata sourced from the linked ABS item, and the footer changed to "Linked to: *[ABS title] · [Library]*" with an unlink icon. Reading progress shown is the unified canonical position from [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md).

**Download-action coupling (Cases 3 and 4):** EPUB-bundle and audiobook-bundle download states are independent on disk — either can be removed without removing the other. The acquire-side is forgiving: tapping **Download readaloud audio** when the EPUB bundle is not yet present fetches both, since the audiobook bundle alone is unplayable. Reading does not require the audiobook bundle.
