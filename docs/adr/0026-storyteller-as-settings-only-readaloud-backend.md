# ADR 0026 — Storyteller is a Settings-only Readaloud backend, not a browsable peer Server

> **Superseded by [ADR 0041](0041-source-and-service-abstractions-replace-server.md)** — the Settings-only/no-browse-surface behaviour is preserved, but the framing changes: Storyteller is a **Service**, not a special-case Server. The "why it's a Server but not really" language this ADR wrote to justify the carve-out is no longer needed.

**Status:** Accepted
**Supersedes:** [ADR 0020](0020-storyteller-as-peer-server-and-readaloud-library.md) (Storyteller as a peer Server type contributing a Readaloud Library).
**Amends:** [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0021](0021-storyteller-abs-matching-with-review-queue.md).

## Context

[ADR 0020](0020-storyteller-as-peer-server-and-readaloud-library.md) made Storyteller a first-class **peer Server**: it appeared in the Server Switcher, contributed a synthetic **Readaloud Library**, and a matched book was browsable and readable from *both* an ABS Library and the Readaloud Library, with display metadata borrowed from the ABS side. ADR 0020 explicitly **rejected** the "sidecar enrichment" shape.

In practice Storyteller has proven to be a thin, awkward content source: a minimal REST surface (`cover, title, authors` only — no description, year, publisher, genres, series, collections, or taxonomy), no native library concept, and a function that **duplicates ABS** (ABS is already Riffle's canonical source for ebooks and audiobooks). The only capability Storyteller adds that ABS lacks is the aligned **[Readaloud]** — synced audio + text. Carrying a whole browsable-Server integration (synthetic Library, two-sided Detail Screen, metadata-borrow rule, Readaloud-side reader) to expose that one capability is disproportionate surface area, and every extra surface is extra bug surface.

The reader already overlays the Storyteller SMIL + audio onto the **ABS EPUB** reading session (via the cross-EPUB index — `ReaderPositionBridge`), and matching already runs proactively on ABS library loads without ever visiting the Readaloud Library (see the 2026-06-03 ABS-side readaloud design). The browsable Storyteller half had become the only part still justifying the peer-Server framing.

## Decision

**Storyteller is a Settings-only backend whose sole job is to be the processor, producer, and provider of Readalouds for ABS books.** Concretely:

- A Storyteller Server is still **added like a Server** (URL + credentials → bearer token in Keystore) and **managed only in Settings**. It contributes **no Libraries**, has **no browsable surface**, and **never appears in the Server Switcher or Navigation Drawer**. The **Readaloud Library is removed.**
- A **Readaloud is a capability attached to an ABS Library Item** via a Confirmed match (ADR 0021), never a standalone browsable item. The matched ABS book reads the **ABS EPUB**; when a Readaloud is linked and its synced bundle is downloaded, the reader overlays audio playback + text highlight (SMIL + audio from the Storyteller bundle, positions translated via the cross-EPUB index). The heavy synced bundle stays an **explicit opt-in download**.
- The link is **ABS-item ↔ Storyteller-readaloud only.** The three [Progress Sync] position holders (ABS ebook, ABS audiobook, Storyteller) are unchanged; this ADR introduces no new linkable entities and no ABS-ebook↔ABS-audiobook linking.
- **No ABS→Storyteller metadata enrichment.** ADR 0020's metadata-borrow rule (a matched Storyteller book displays ABS metadata in the Readaloud Library) is **deleted** — there is no longer anywhere to display a Storyteller book. Storyteller's `cover, title, authors` are used **only as matcher input** and are never shown to the user.
- **Matching and the review queue are unchanged**, and remain the **only** link-management surface (Settings → Storyteller Server → Readaloud matches: Pending Review, Unmatched, Confirmed, including Unlink). The ABS Library Item Detail Screen gains **no** new link affordances — it keeps only the existing readaloud indicator + reader readaloud control when a link exists.

## Consequences

- **Storyteller-only deployments are no longer supported.** A user with a Storyteller Server but no ABS Server has nothing to read — Storyteller contributes no browsable content and there are no ABS items to attach Readalouds to. This is a deliberate, explicit reversal of an ADR 0020 promise; do not "restore" it as if it were an oversight. A Storyteller Server is useful only alongside ≥1 ABS Server.
- ADR 0020's **four-case Detail Screen collapses to the two ABS-side cases** (no-match = today's behaviour; matched = adds readaloud indicator + reader readaloud control + explicit bundle download). The two Readaloud-side cases are gone.
- **[ADR 0019] is amended.** The reconciliation engine is **unchanged** (three-peer remote set, single unified canonical position, last-update-wins with one inbound winner per cycle, per-target failure isolation, eager small-prerequisite fetch on Confirmed match, opt-in audio bundle). What changes is that **"opened side" stops being a variable** — a book is always opened from the ABS side and the reader always displays the **ABS EPUB**. Three specific simplifications follow:
  - **Canonical frame is permanently the ABS EPUB.** The canonical position is always an ABS-EPUB Locator, so **ABS ebook** is always the native peer (CFI ↔ canonical, no cross-EPUB translation) and **Storyteller** + **ABS audiobook** are always the translated peers (ST→ABS via the cross-EPUB index; audio seconds → SMIL → ST → ABS). `ReaderPositionBridge.displayedSide` is always `ABS`; the `Domain.ST`-as-displayed path and the reverse (Storyteller-native) translation direction become dead.
  - **`BookSyncState.openedSide` (and `OpenedSide.READALOUD`/`STORYTELLER`) is removed.** `applicableRemotes`' `sidePeer` collapses from a `when(openedSide)` to the constant `{ABS ebook}`.
  - **The single-peer `{Storyteller}` sync case is gone.** An unmatched Storyteller publication is never opened, so it never enters a sync cycle. Unmatched books always sync `{ABS ebook}`, and a matched book whose prerequisites aren't cached yet falls back to `{ABS ebook}` (no longer side-dependent) until it upgrades to the three-peer set. The matched cases — three-peer when prerequisites are cached, and the multi-link guard's `{ABS ebook, ABS audio}` — are unchanged.
- **[ADR 0021] is amended:** the review queue stays exactly as specified, but its *secondary* entry point (the Readaloud-side Detail Screen footer) is removed along with that screen. Matching still pulls Storyteller books into `library_items` as matcher input only — never as browsable entries.
- **Annotations** now work during all reading (they always did on the ABS EPUB, which is now the only reading surface); ADR 0024's "ABS-side only" deferral is no longer a limitation in practice.
- **[ADR 0023] is unaffected:** the Storyteller synced bundle remains the single source of Readaloud audio + SMIL.
- Dead code from the removed browsable half (Readaloud Library navigation/screens, Readaloud-side Detail cases, Storyteller-only reader/open paths, `OpenedSide` Storyteller branches) should be removed rather than left guarded-but-unreachable.

## Alternatives considered

- **Keep ADR 0020's peer-Server + Readaloud Library.** Rejected: it is a large, duplicate-of-ABS integration surface whose only unique value (the Readaloud) is already deliverable on the ABS item. Surface area is bug surface.
- **Keep Storyteller-only deployment support** (e.g. a minimal Storyteller-only library). Rejected: it is the entire reason the browsable half existed, and ABS is the canonical content source; supporting a strictly-weaker Storyteller-only browse experience is not worth its cost.
