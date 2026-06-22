# ADR 0033 — Annotation sync: Google Drive appDataFolder as first cloud target, not WebDAV

**Status:** Accepted
**Supersedes:** [ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md) planning assumption (still keeps the pluggable `AnnotationSyncTarget` interface and W3C format).

## Context

[ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md) designed annotation sync with a pluggable `AnnotationSyncTarget` interface and deferred cloud sync behind that abstraction. The planned first target was WebDAV on a Synology NAS over Tailscale, chosen for self-hosting and privacy. However, this requires:

- Users to own or run NAS hardware (or rent self-hosted infrastructure)
- Tailscale setup and tailnet configuration
- WebDAV server setup and troubleshooting
- SSL/TLS certificate management
- Manual device authentication (WebDAV credentials)

This represents significant friction for adoption. A simpler alternative would be Dropbox (native SDK, zero infrastructure), but **Dropbox SDK requires Android 8+ (SDK 26+), and Riffle supports Android 7.1.1 (API 25)**, making it incompatible.

Google Drive offers both simplicity and compatibility: its **appDataFolder** scope creates an app-specific, hidden folder that is:
- Invisible to users and other apps (no clutter in their Drive root)
- Automatically cleaned up when the app is uninstalled
- Still user-controlled (deletable, backed up with Drive backups)
- Accessed via standard OAuth2 (no manual credential setup)
- **Compatible with Android 7.1.1+** (REST API v3 has no SDK version floor)

## Decision

Replace the planned **WebDAV/NAS first target with Google Drive appDataFolder**. This keeps the pluggable architecture from ADR 0025 intact but changes the concrete first implementation:

- **First target is Google Drive appDataFolder** (via Google Drive REST API v3), not WebDAV.
- **Why Google Drive appDataFolder first:**
  - **Zero user infrastructure.** Users link their existing Google account; no NAS, no Tailscale, no server setup.
  - **Better privacy by design.** AppDataFolder is app-specific and hidden; invisible to users, other apps, and in Drive UI. Automatically cleaned up on uninstall. Data stays in user's own Google account (not third-party server).
  - **Standard OAuth2.** Google authentication is ubiquitous; users already trust it. `DRIVE_APPDATA` scope is narrowly scoped.
  - **User control.** Annotations back up with Drive backups, are deletable by the user, but invisible in their Drive root (no clutter).

- **Same W3C format and per-device-file merge model** from ADR 0025 apply unchanged. Annotation files remain `<serverId>/<itemId>/annotations-<deviceId>.jsonld` in appDataFolder, and merge semantics stay per-record last-write-wins.
- **Same `AnnotationSyncTarget` abstraction.** Google Drive is one concrete target; future targets (WebDAV, Dropbox, ABS native API) plug in via the same interface without touching the format or merge.
- **Authentication via OAuth2.** Riffle will request `DRIVE_APPDATA` scope (app-folder only, no access to user's other files); tokens are stored in Android Keystore.
- **Fallback on auth expiry:** Prompt user to re-authenticate (per user's requirements), not degrade to local-only silently.

## Considered options

- **WebDAV on NAS (original ADR 0025 plan).** Rejected: high infrastructure friction (NAS ownership, Tailscale, SSL certs, WebDAV troubleshooting) delays shipping and limits adoption. WebDAV remains available as a second target if users request true self-hosting.
- **Dropbox.** Rejected: simpler implementation (native SDK, built-in conflict resolution) but **Dropbox SDK requires Android 8+ and Riffle supports Android 7.1.1**, making it incompatible with the app's minimum SDK. Google Drive REST API v3 works on 7.1.1+.
- **Keep local-only permanently (no sync).** Rejected: annotations are already designed for sync; deferring sync permanently means losing that design benefit.

## Consequences

- **Privacy model improved.** Data lives in the user's own Google account (not a third-party server), in an app-specific hidden folder. Annotations are not visible to other apps or in Drive UI. If users require true self-hosting (on-premises), WebDAV can be implemented as a second target later without format or merge changes.
- **Annotation sync ships in v2** (after local-only v1 is stable and in use).
- **Future targets can coexist.** The same book can sync to Google Drive on one device and WebDAV/Dropbox on another; per-device files make this safe.
- **No E2E encryption in v1.** Data on Google Drive is encrypted in transit (HTTPS) and at rest (Google's default); payload-level encryption can be added as a wrapper around the Google Drive target without changing the interface or format if users request it.
- **Quota awareness.** Google Drive free tier is 15GB (shared with Gmail/Photos); annotation storage is lightweight (~1-10MB per book). Consider a warning in Settings if annotation storage approaches quota.

## Migration to ABS (Future)

When Audiobookshelf implements native annotation endpoints (POST/GET/DELETE for highlights, notes, bookmarks), Riffle will support migrating annotations from Google Drive → ABS:

- **ABS target will plug in via the same `AnnotationSyncTarget` interface.** No format changes needed; ABS will emit and consume the same W3C Annotation objects.
- **Migration mechanism:** On-demand in Settings — "Migrate annotations to Audiobookshelf" button that:
  1. Reads all local + remote (Google Drive appDataFolder) annotations
  2. Pushes each non-deleted annotation via ABS endpoint (idempotent by UUID)
  3. On success, archives the Google Drive files (keeps tombstones for safety; purge later)
  4. Switches the sync target from Google Drive to ABS for future edits
- **No data loss:** W3C format round-trips losslessly; CFI anchors, timestamps, device provenance, notes all survive the migration.
- **Backward compatibility:** If user reverts (ABS sync fails), fallback to Google Drive without re-syncing everything (dirty flags track what changed).

This design ensures that Google Drive is not a terminal choice — it's a safe starting point, and upgrading to ABS (once available) is a smooth one-way data migration.

## Export/import backups (future)

Support annotation export to local JSON and import from backup (per ADR 0025's "deferred" status) as a separate feature. This gives users a local backup path independent of sync transport.
