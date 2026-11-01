---
name: reference-komga-server
description: "Komga test server address + credentials — media-server:25600, test@test.test / test"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 23671580-abde-44cc-9a94-aaa5a9e1523a
---

Komga test instance for verifying Riffle Komga integration:

- Base URL: `http://media-server:25600`
- Username: `test@test.test`
- Password: `test`
- Auth: HTTP Basic (Komga default).

Actuator (`/actuator/info`) is admin-gated so returns 401 for the test user; `GET /api/v1/libraries` returns 200 and is the healthcheck Riffle uses.

Landed alongside the Komga source in PR #531 (2026-07-10 area). Companion peers to [[reference_abs_server]] and [[reference_storyteller_server]].
