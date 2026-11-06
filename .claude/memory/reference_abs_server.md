---
name: reference-abs-server
description: Audiobookshelf test server credentials for direct API testing during development
metadata: 
  node_type: memory
  type: reference
  originSessionId: afe6f669-709c-4718-aa19-7143c23b219a
  modified: 2026-09-29T11:07:59.742Z
---

Audiobookshelf server: http://media-server:13378
Username: test
Password: test

2026-09-29: User confirmed username is `test` (not `test2`). Verified via curl: `test`/`test` returns HTTP 200 with JWT, `test2`/`test` returns HTTP 401. Previous note about `test2` was incorrect — the `test` account works fine now.

Use `/login` endpoint to get a fresh token. The stored token may expire.

Official ABS Android client (reference for API structures & data models): https://github.com/advplyr/audiobookshelf-app

Libraries:
- Books: `e77c113d-4383-488d-956f-89c18db431ac`
- Audiobooks: `a688b399-cacc-412c-bb79-d111692ba64f`
- Fairy Tales: `f1a32c6e-c861-432c-960b-a59697ef7108`
- Podcasts: `3a7a392b-a37c-4413-944c-81329c33c5d6`
- Radio Theater: `6bac96cc-c840-4509-83dd-da6262b6163e`
- Comics: `be640194-be8f-482f-9b13-58d294d20f7b`
- Magazines: `39233a20-cb00-45e8-93ea-221dddfeb425`
