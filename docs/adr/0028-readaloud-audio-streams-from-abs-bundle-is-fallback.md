# ADR 0028 — Readaloud audio streams from the ABS audiobook; the Storyteller bundle becomes a fallback

**Status:** Proposed
**Supersedes (on implementation):** [ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md) (the Storyteller synced bundle is the Readaloud audio source).
**Amends:** [ADR 0024](0024-drop-per-server-audio-cache-cap.md) (drop per-server audio cache cap), [ADR 0026](0026-storyteller-as-settings-only-readaloud-backend.md) (Storyteller as a Settings-only Readaloud backend).

## Context

[ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md) made the Storyteller **synced bundle** the single source of both the readaloud audio and the SMIL. The bundle is ~300–500 MB per book, must be **fully downloaded before playback**, and **duplicates audio ABS already serves** (Riffle separately downloads the ABS EPUB to read from, since the reader displays the ABS EPUB — ADR 0026).

Probing the development Storyteller server established the bundle's real shape:

- The bundle's audio is the **same recording ABS serves**, re-split into fixed segments — stream-copied, not transcoded, for most books (verified: Storyteller's *ingested-source* `fileSize` for *The Martian* is byte-identical to ABS's file). A minority are re-encoded to AAC.
- The **non-audio part** — the `.smil` overlays plus the aligned chapter text — is ~1.3 MB and **range-extractable** from `/synced` (the endpoint honours `Accept-Ranges`). There is no per-resource endpoint, but the central directory + entry ranges are cheap to fetch.
- `GET /api/v2/books/{id}` returns, audio-free, the audiobook's per-track durations and the ingested-source `fileSize`/`duration`.

So the bundle's audio is **reconstructable** from (a) ABS's audiobook, streamed, plus (b) a small Storyteller **sidecar** — turning the mandatory multi-hundred-MB pre-download into an optional one.

## Decision

A matched [Readaloud] chooses, per book, between two paths at open time:

- **Streaming path** — taken when **both** the ABS ebook and audiobook are linked **and** the audiobook passes an **identity check** (Storyteller's ingested-source `fileSize` + `duration` + per-track-duration-vector equal ABS's). Audio is **streamed from the ABS audiobook** (HTTP `Range`), eager-completing the whole file into cache as it plays. Alignment comes from the Storyteller **[Readaloud Sidecar]** (SMIL + chapter text, range-extracted from `/synced`); the cross-EPUB index is built from the ABS ebook + the sidecar text. The full bundle is never fetched.
- **Bundle path (fallback)** — taken otherwise (audiobook not linked, or identity mismatch). The Storyteller synced bundle works exactly as ADR 0023 specifies.

The **identity check is what makes the streaming path safe**: it is taken only when ABS's audio is *provably* the recording Storyteller aligned against, so a name-matched-but-different audiobook never silently mis-syncs the highlight.

Supporting sub-decisions:

- **Caching (amends ADR 0024).** Audio plays through a progressive byte-range cache (ExoPlayer `SimpleCache`): partial and gradual. Playback eager-completes the file ahead of the playhead. Stream and download are the *same* cache — partial+evictable vs complete+pinned. The audio tier carries an **app-managed LRU cap with a fixed internal default** (no user-facing config) and lives in **app-owned storage**, because `SimpleCache` corrupts if the OS clears spans under it. This narrows ADR 0024's "no cap, OS-managed eviction" to the **ebook** tier.
- **No metered/Wi-Fi branch.** Playback eager-completes on any connection (Riffle has no metered-network detection today).
- **UX.** The reader is unchanged for streaming books (instant play). A download-only book shows a greyed-but-tappable headphones button whose tap explains "not downloaded yet" and points to book details. **Book details is the single download surface** — its Download Button pins the ABS ebook + ABS audiobook + sidecar. The Settings **Readaloud matches** screen carries per-row status: *Streaming* / *Download only · no audiobook linked* / *Download only · audio doesn't match*. (This adds status to the matches screen only — it keeps ADR 0026's Detail Screen free of link affordances.)

## Open risk (gates acceptance)

The bundle's highlight is exact **by construction** (the SMIL plays against the bundle's own audio). The streamed path instead relies on a Storyteller-segment → ABS-track time map, which can drift at segment boundaries and from AAC encoder priming on transcoded books. Before the streaming path is trusted as the default for eligible books, **validate empirically that the streamed highlight lands as tightly as the bundle's** — on a clean book (Project Hail Mary) and a transcoded one (1984 / Fellowship).

## Consequences

- Streaming-eligible books **start instantly** with no upfront download; the bundle's duplicate audio is eliminated for them.
- The bundle path survives for unmatched/mismatched books → **no regression**; two playback paths coexist, selected by an *observable* per-book predicate (links + identity).
- The [Readaloud Sidecar] is ~1 MB, so it can be fetched **eagerly on Confirmed match** — removing the constraint in `CrossEpubIndexBuilderService` that defers the cross-EPUB index until the heavy bundle is present. Three-peer progress sync prerequisites become available without a heavy download.
- The bundle path stays alive as code (downloader + zip playback), kept for correctness, not redundancy.

## Alternatives considered

- **Retire the bundle entirely (single ABS-sourced path).** Rejected: a name-matched audiobook can be a *different recording*, and some audiobooks won't match by name at all — in both cases the bundle is the only correct/available audio. The identity guard plus the bundle fallback keeps correctness without ever losing readaloud. (This reverses an earlier in-discussion lean toward full retirement, once the divergent-source case was made concrete.)
- **Stream-only, no eager completion.** Rejected: leaves gaps on seek and makes offline network-dependent; eager completion makes offline emerge from a normal listen.
- **Gate play on Wi-Fi/metered.** Rejected: no such policy or detection exists today (`ConnectivityObserver` is online/offline only); play eager-completes on any connection.

## Note

A survey of the dev server found some bundles are **under-produced** (no audio, or only a few chapters aligned) yet still report `status: ALIGNED`. That is a separate matching/availability concern, not addressed here, but it reinforces that availability gating must inspect actual content rather than trust Storyteller's status.
