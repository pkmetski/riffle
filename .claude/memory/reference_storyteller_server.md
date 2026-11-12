---
name: reference-storyteller-server
description: "Local Storyteller server URL and credentials for testing Riffle's Storyteller integration"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 6a156759-0c0a-44ca-b087-139cc49e1e50
---

Storyteller server for development/testing:

- URL: `http://media-server:8001`
- User: `test`
- Password: `test`

Auth endpoint: `POST /api/token` as **multipart/form-data** with `username` + `password` fields → returns `{"access_token": "...", "token_type":"bearer", ...}` (see `StorytellerApiClient.login`). NOT `/api/v2/token` (that path 400s). curl: `curl -X POST http://100.102.186.26:8001/api/token -F username=test -F password=test`. Bundle endpoints: `GET /api/books?synced=true` (list synced readalouds), `HEAD|GET /api/books/{storytellerBookId}/synced` (the media-aligned EPUB bundle, Bearer auth). The synced bundle is a Stored-mp3 EPUB-3 with sentence-level `.smil` Media Overlays (clips abut exactly; audio is one ~54MB Stored mp3 per file-group, clip offsets reach deep, e.g. 3470s).
Stored alongside the ABS server in [[reference-abs-server]] under the same Keystore namespace.

Riffle integration is tracked under [[project-riffle-status]]; see ADR 0020 (`docs/adr/0020-storyteller-as-peer-server-and-readaloud-library.md`) for the architectural decision (Storyteller is a peer Server type contributing a ReadAloud Library).
