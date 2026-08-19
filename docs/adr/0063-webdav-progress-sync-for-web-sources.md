# ADR 0063 — WebDAV Reading Progress Sync for Web Sources

**Status:** Proposed

## Context

[Web Source]s (Chitanka, Gutenberg, and any future `SourceType` with `isWebSource = true`) have no user account and therefore no server-side progress peer. Reading progress lives only on-device and is lost on reinstall. A user reading across two devices has no way to resume where they left off.

The annotation sync WebDAV target (ADR 0042) already has a configured connection, a path-naming convention, and a per-source `syncNamespace`. The progress reconcile machinery (ADR 0036) is already abstract over a `ProgressRemote<P>` interface and knows nothing about whether the remote is an ABS REST endpoint or a WebDAV file.

## Decision

Extend the WebDAV sync target to also carry **reading progress for Web Sources**, reusing the existing reconcile algorithm and dirty-row sweep unchanged.

### Shared WebDAV connection

No new configuration screen. If the annotation sync WebDAV target is configured, Web Source progress sync is automatically active. If it is not configured, progress sync for Web Sources is unavailable — silently, with no user-facing error.

### File layout

One file per book, at:

```
{basePath}{namespace}__{itemId}__progress.json
```

Same flat layout and `__` separator as annotation files. `namespace` is the source row's `syncNamespace` (the same value annotation sync already uses for this source). `itemId` is the stable public identifier for the book (Chitanka slug, Gutenberg number).

A single file per book — not one per device — because progress is a scalar that replaces itself. There is no merge step: last-write-wins via the file's `Last-Modified` header, which serves as the authoritative server timestamp for the reconcile algorithm.

### File content

```json
{
  "position": "<Readium Locator JSON>",
  "readingProgress": 0.42,
  "finishedAt": null,
  "lastUpdate": 1723987200000
}
```

`position` is always Readium Locator JSON — Web Sources use `CfiDialect.READIUM_NATIVE`, so no CFI translation is needed. `lastUpdate` is the epoch-ms timestamp the local device recorded when it last wrote the position; `Last-Modified` on the WebDAV file is the reconcile timestamp used by the sweep.

### Reconcile integration

`CatalogProgressRemoteFactory` grows a second branch: if the source has no `ProgressPeerCapability` **and** `sourceType.isWebSource` **and** the WebDAV target is configured, return a `WebDavProgressRemote<String>` backed by a GET + PUT against the progress file. The `ProgressReconciler` and dirty-row sweep are unmodified.

`WebDavProgressRemote.get()` issues an HTTP GET; the `Last-Modified` response header becomes `RemoteProgress.lastUpdate`. `WebDavProgressRemote.patch()` issues an HTTP PUT and reads back `Last-Modified` as the server stamp to adopt.

### UI

The WebDAV settings section and screen are renamed from "WebDAV annotation sync for Komga" to **"WebDAV sync"**. The description is updated to explain both covered concerns: annotation sync (Komga and future server sources) and progress sync (Chitanka, Gutenberg, and future web sources). No separate status surface is added for progress sync — failures are retried silently by the sweep.

### Extensibility

Any future `SourceType` with `isWebSource = true` automatically participates: the factory branch gates on the flag, not on an enumerated type set.

## Alternatives considered

**Per-device progress files (like annotations).** Annotations are additive — two devices each have highlights the other lacks. Progress is a scalar — only the newest value matters. Per-device files would require a client-side "pick the newest" merge step with no benefit over a single file.

**Separate WebDAV configuration.** Would let users point progress sync at a different server from annotation sync. Rejected: the added configuration surface adds friction with no meaningful use case; users who want separation can put annotation and progress files in different subtrees of the same server.

**Push-on-close / pull-on-open only, no sweep.** Simpler, but leaves a gap: if the device goes offline mid-read and the user never closes the reader cleanly, the position is never pushed. The dirty-row sweep already handles this case for ABS and Komga; extending it to WebDAV costs nothing and closes the gap.
