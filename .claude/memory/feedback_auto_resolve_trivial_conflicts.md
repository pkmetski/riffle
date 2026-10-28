---
name: feedback-auto-resolve-trivial-obstacles
description: "Auto-resolve mechanical obstacles (rebase param/import unions, stale generated code, missing test args after new ctor params) instead of stopping to ask"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 4b10e84e-ef9f-47f1-916f-9a53d27169bf
---

When a workflow (rebase, build, test run) hits an obstacle whose resolution is mechanical and unambiguous, fix it and continue. Don't stop and ask. Examples:

- Rebase conflict where both sides added a parameter to the same list, added an import, added an entry to a registry.
- Stale Hilt/KSP/codegen output that just needs a clean rebuild.
- Test compilation breaks because a new constructor parameter wasn't wired into a test helper or harness — add the obvious default and continue.

**Why:** Stopping to confirm trivial unions wastes the user's time. They've already authorized the workflow by invoking `/finalize` (or similar). Syntactic unions and stale-state cleanups aren't judgment calls.

**How to apply:** Only escalate when resolution requires a real judgment call — overlapping edits to the same logic, semantic ambiguity, unclear intent. See [[finalize]] skill (updated to reflect this).
