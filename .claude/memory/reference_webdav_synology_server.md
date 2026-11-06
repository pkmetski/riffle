---
name: webdav-synology-server
description: "WebDAV credentials and URL for the user's Synology DSM WebDAV Server (annotation sync test target)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: f24678fa-8655-4b57-b3f3-f7d383126b1f
---

`https://pkmetski.synology.me:5006/` — user's Synology DSM, WebDAV Server package over HTTPS.
- User: `webdav`
- Password: `7E$8makncPW^Zmo`
- Writable share: `/Annotations/` (Read/Write granted to `webdav` user in DSM Control Panel → Shared Folder → Annotations → Permissions).

For Riffle annotation-sync testing the URL configured in Settings is
`https://pkmetski.synology.me:5006/Annotations/` — the share must appear as a path segment; the
root (`/`) is just a share enumeration endpoint and isn't writable.

Known DSM WebDAV quirks observed (Apache + DSM mod_dav):
- Default `okhttp/x.y.z` User-Agent gets 424 ("Failed Dependency") on MKCOL even when share permissions allow folder creation. Use Finder UA — `WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)` — and MKCOL goes through.
- PROPFIND on a not-yet-existing collection returns 405 (immediate child of share) or 400 (deeper subpath), not 404.
- PUT to a path with missing parent dirs returns 400 or 405, not 409.
- MKCOL on bare-UUID subpaths (`/share/<uuid>/`) sometimes returns 400 even with the Finder UA + empty body + Content-Length: 0 — same MKCOL succeeds for `<uuid>-something`. We've worked around this by storing per-device files in a *flat* namespace under the share with `<serverId>__<itemId>__<filename>` composite names (commit `dd6b1f3b` and follow-ups).

Use `curl -ksv -u webdav:'7E$8makncPW^Zmo' …` to probe directly — single-quote the password so the shell doesn't interpret the `$8` as a variable.
