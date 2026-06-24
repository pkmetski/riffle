# ADR 0035 — Annotation sync: WebDAV as first concrete target (withdrawing ADR 0033)

**Status:** Accepted
**Withdraws:** ADR 0033 (Google Drive appDataFolder as first cloud target) — never implemented; the underlying assumption no longer holds.
**Reaffirms:** [ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md) (pluggable target, W3C format, per-device-file merge) — its **original** first-target plan: WebDAV.

## Context

[ADR 0033](https://github.com/plamen-kmetski/riffle/blob/main/docs/adr/) (now deleted from the tree, recoverable via git history) proposed Google Drive `appDataFolder` as the first cloud sync target because:

1. Zero user infrastructure (no NAS, no Tailscale).
2. Standard OAuth2.
3. The `appDataFolder` scope hides data from the user's Drive UI.

Time has moved on and the trade-off no longer favours Google Drive:

- **OAuth2 cost on Android 7.1.1.** Google's modern auth libraries (`androidx.credentials` / Credential Manager) require API 23+ but in practice are tested against far newer surfaces; the legacy `GoogleSignIn` API is deprecated; either path forces a substantial wrapper. WebDAV needs only OkHttp + HTTP basic auth, which already ships in the project.
- **`appDataFolder` is not a privacy story.** Files there are still in the user's Google account, still subject to Google's scanning policies, and still go away when the user uninstalls — that's a *data-loss* mode, not a privacy win.
- **WebDAV reaches a wider audience than expected.** Nextcloud, ownCloud, Synology, Box, Apache `mod_dav`, and self-hosted setups all speak it; many users already have a target without buying anything new. The original "needs NAS hardware" framing in ADR 0033 was too narrow.
- **No third-party SDK Android-version floor.** WebDAV over OkHttp works on every API level Riffle supports today and into the future.

## Decision

**WebDAV is the first concrete `AnnotationSyncTarget`** — exactly what ADR 0025 originally planned. ADR 0033 is withdrawn; Google Drive may resurface later as one option among several, but not as *the* first cloud target.

- **Transport:** `PROPFIND` / `GET` / `PUT` / `MKCOL` / `DELETE` via OkHttp against a user-supplied WebDAV URL.
- **Path layout:** `<base>/<absUserId>/<itemId>/annotations-<deviceId>.jsonld` — `<absUserId>` is the ABS `/api/me` `user.id`, persisted on `Server.absUserId`. The local Riffle `servers.id` is **not** the path key: it's a per-device random UUID, which would silently break cross-device sync (each device would write under a different prefix and never see each other's files). See [ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md) for the full rationale. The flat-file physical encoding `<base>/<absUserId>__<itemId>__<filename>` works around Synology DSM's MKCOL gating without changing the logical layout.
- **Auth:** HTTP basic, with digest as a follow-up if a real-world server demands it. Credentials stored Android-Keystore-encrypted, never plaintext.
- **One global config**, not per-Server — per-account scoping is preserved by the path namespacing.
- **Test Connection:** `PROPFIND` on the base directory, `MKCOL` if it 404s, surface bad-URL / 401 / TLS / network as distinct errors.
- **Same `AnnotationSyncTarget` abstraction** as the local-directory scaffold from ADR 0034 — swapping targets does not touch the format, codec, or merge.

## Considered options

- **Stay on Google Drive (ADR 0033).** Rejected for the reasons above (OAuth2 weight on the supported Android floor; weak privacy story; uninstall-deletes-data).
- **Both at once.** Rejected: builds a multi-target UI before a single one is proven. Add a second target only when there's friction users actually report.
- **No cloud target, ever.** Rejected: ADR 0025 already designed for sync; deferring permanently wastes the existing schema/codec/merge work.

## Consequences

- **Self-hosters served first.** Users with Nextcloud, ownCloud, Synology, or any WebDAV server can sync immediately; no app-side OAuth integration to maintain.
- **Future targets remain easy.** Dropping in Google Drive, S3, or an ABS-native endpoint later is one more `AnnotationSyncTarget` implementation plus a config row — the per-device-file format and merge stay frozen.
- **No payload encryption in v1.** Transport is HTTPS only; users who don't trust their server (e.g., shared Nextcloud) will want payload encryption — deferred behind the same interface.
- **No automatic GC.** Same policy as ADR 0025: tombstones and orphan files are tiny and stay indefinitely.

## References

- [ADR 0025 — Annotation sync: pluggable target, W3C format, per-device-file merge](0025-annotation-sync-pluggable-target-w3c-format.md)
- [ADR 0034 — Local-directory scaffold + merge service](0034-annotation-sync-local-directory-scaffold-and-merge-service.md)
- [Issue #76 — feat(annotation-sync): WebDAV sync target + global sync config](https://github.com/plamen-kmetski/riffle/issues/76)
