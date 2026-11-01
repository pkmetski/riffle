---
name: reference-abs-status-endpoint
description: "ABS exposes its version on the unauthenticated /status endpoint as `serverVersion`. /api/server-info does not exist."
metadata: 
  node_type: memory
  type: reference
  originSessionId: ef33f8a6-39b2-4090-a7ce-5cd05e05c6a4
---

ABS version endpoint: `GET /status` (no auth, returns 200 even for logged-out clients).

Response shape (verified against ABS 2.35.1):
```json
{
  "app": "audiobookshelf",
  "serverVersion": "2.35.1",
  "isInit": true,
  "language": "en-us",
  "authMethods": ["local"],
  "authFormData": { "authLoginCustomMessage": "" }
}
```

Field name is `serverVersion`, not `version`.

Confirmed non-existent on ABS (return 404 even with a valid Bearer token):
`/api/server-info`, `/api/serverinfo`, `/api/server/info`, `/api/info`, `/api/status`, `/api/version`, `/api/server-version`.

Other unauthenticated endpoints worth knowing:
- `/healthcheck` → `OK` (plain text)
- `/ping` → `{"success":true}`
Neither carries a version.

See [[reference-abs-server]] for credentials.
