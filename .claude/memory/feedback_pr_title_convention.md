---
name: feedback-pr-title-convention
description: "Use Conventional Commits prefixes on PR titles (feat/fix/docs/build/etc.) with a scope, matching the repo's commit-message style"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: ebff92c3-2110-4500-ae8e-b071cfc624e2
---

PR titles in this repo follow Conventional Commits with a scope, e.g. `fix(reader): apply Margins setting`, `feat(tablet): permanent nav drawer`, `build(harness): add make target`. Bare descriptive titles look "lame" (user's word) and break the visual scan of `gh pr list`.

**Why:** consistency with the commit-message style already used across the project; allows changelog tooling and humans to filter by change type at a glance.

**How to apply:** when running `gh pr create`, always prefix the `--title` with `type(scope):` matching the dominant change type. If a change is mostly a bug fix with new UX on top, prefer `fix(...)` and mention the addition in the body. Scope picks the user-facing area (`to-read`, `reader`, `tablet`, `harness`, `release`, etc.), not the module path.
