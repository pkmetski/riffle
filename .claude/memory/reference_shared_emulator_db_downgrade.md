---
name: ""
metadata: 
  node_type: memory
  originSessionId: 4a1a080c-2ed5-4289-a986-5a9a5e916578
---

A shared test emulator (the API-25 `Harness_Medium_Phone`) carries **one** `riffle.db`, written by whichever workspace last installed. With ~40 parallel Conductor workspaces, a sibling branch may have bumped the Room `@Database` version higher than yours and installed first. When you then install YOUR (lower-version) build, Room throws:

`IllegalStateException: A migration from <higher> to <yours> was required but not found` (a downgrade) → app crash-loops at first DB access.

**This is an environment artifact, not a bug** — a real user upgrading from a published build is always at an equal-or-lower version. Fix on the device with `adb -s <serial> shell pm clear com.riffle.app` (wipes the DB so Room recreates it fresh at your version; note this also clears configured servers). Relates to [[reference_harness_avd_contention_flake]].
