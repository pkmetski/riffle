---
name: feedback-ephemeral-avd-per-session
description: "Whenever AVD interaction is needed, create a throwaway AVD cloned from an Android 7.1.1 base, use it for the whole session, delete it on finalize"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: de1a4a34-d052-4c74-a918-e04023b027a6
---

Whenever an interaction with an AVD is needed during a session (harness tests, manual verification, WebView DevTools, or any emulator work):

1. **Create** a new AVD at session start, based on an existing AVD with Android 7.1.1 (API 25). Give it a session-unique name.
2. **Use** that one AVD for every verification and interaction needed during the entire session.
3. **Delete** the AVD on finalize.

**Why:** Sessions run in parallel (Conductor workspaces). Sharing a single named AVD (e.g. `Harness_Medium_Phone`) causes interference — boot contention, shared-DB version downgrade crashes, and "Process crashed" flakes when two workspaces grab the same AVD. A per-session throwaway AVD eliminates this cross-session interference.

**How to apply:** At the start of any session that will touch an emulator, clone the API-25 base AVD to a unique name and point the relevant tooling at it. Tear it down when wrapping up. Relates to [[reference_harness_avd_contention_flake]], [[reference_shared_emulator_db_downgrade]], and [[reference_api25_avd_boot_incantation]] (the API-25 boot needs CI flags — carry those into the cloned AVD).
