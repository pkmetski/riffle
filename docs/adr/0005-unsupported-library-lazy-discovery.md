# ADR 0005 — Lazy discovery of Unsupported Libraries and Library Items

## Status
Accepted

## Context
ABS does not expose a reliable library-level signal to distinguish ebook libraries from audiobook libraries. Both share `mediaType = "book"`; the `audiobooksOnly` flag is not consistently set by server operators. Prefetching items for all libraries to classify them upfront is not acceptable for users with large libraries (potentially thousands of items across many libraries).

## Decision
Classify Libraries and Library Items as Unsupported lazily, on first item fetch:

- **Library Items** — when `refreshLibraryItems` runs for a library, every item returned without a `media.ebookFile` is stored as an Unsupported Library Item. Supported and Unsupported items are both persisted in Room.
- **Libraries** — after each `refreshLibraryItems` call, if the library has ≥1 fetched items and *all* of them are Unsupported, the Library is marked Unsupported in Room. A library with zero items is not marked Unsupported.
- **Re-evaluation** — every call to `refreshLibraryItems` re-evaluates the library's supported state, so a library can become supported again if ebook items are added on the server.
- **UI** — Unsupported Library Items and Unsupported Libraries are displayed as dimmed and non-tappable. This applies to any screen that lists Libraries or Library Items (including a future library-selection screen at server-addition time).

## Alternatives considered
- **Filter at network layer** — discard items without `ebookFile` before storing. Rejected because it makes libraries with only audiobooks appear mysteriously empty rather than explaining why.
- **Prefetch all items at library-list load** — classify libraries before the user opens them. Rejected due to performance: impractical for large libraries.
- **Hide audiobook libraries entirely** — not achievable without a reliable server-side signal.
