# ADR 0060 — Prebuilt Dictionary Packs for Word Lookups

## Status

Accepted.

## Context

[Word Lookup] needs to work offline, infer the lookup language from the current book, and return v1 results as [English Gloss]es. Open upstream dictionary data exists through Wiktionary-derived projects such as Wiktextract/Kaikki, but their raw dumps are large, frequently changing, and shaped for data interchange rather than fast mobile lookup.

## Decision

Riffle will consume **prebuilt Riffle Dictionary Packs**, not raw upstream dictionary dumps, at runtime. A separate build pipeline periodically transforms upstream Wiktionary-derived data into compact per-language packs and publishes a manifest; the app downloads the pack for the inferred book language after the user accepts the first install prompt, then refreshes accepted packs in the background.

Each installed pack is a local [Dictionary Pack]. It is selected by book language and returns English Glosses in v1. Lookup history is stored separately from the pack so user history survives pack refreshes.

## Considered Options

- **Download raw Wiktextract/Kaikki JSONL on-device.** Rejected: it would push large downloads, parsing cost, upstream schema drift, and indexing work onto the phone.
- **Launch Android dictionary apps via `ACTION_DEFINE`.** Rejected as the primary path: it can define selected text externally but returns no structured result for Riffle's unified view and does not cover Riffle's min API uniformly.
- **Bundle all dictionaries in the APK.** Rejected: it bloats installs for languages the user may never read and prevents dictionary refreshes from shipping independently of app releases.

## Consequences

- Riffle needs a hosted pack manifest and a repeatable pack-builder job before Word Lookup ships.
- Dictionary updates can be smaller and safer than app updates, but the app must handle pack schema versions and stale/failed updates.
- Licensing and attribution for Wiktionary-derived data are part of the pack metadata and Settings surface, not optional copy.
