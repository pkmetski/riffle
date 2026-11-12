---
name: never-touch-android-ci-timeout
description: Never change Android CI job timeouts when fixing iOS harness failures; Android timeouts are unrelated and should stay at their current values
metadata:
  node_type: memory
  type: feedback
  originSessionId: c776e08b-91f2-449e-af42-ce28e69bcda9
  modified: 2026-10-01T06:31:18.648Z
---

Never change Android CI job timeouts when investigating or fixing iOS harness failures. The Android harness phone job timeout is **40 min** — restore it to this if it is ever changed.

**Why:** Android timeout issues and iOS timeout issues are completely independent. Bumping Android timeouts while chasing an iOS failure pollutes git history with unrelated changes and has to be reverted. The user had to explicitly revert `ci: raise iOS harness phone 50→65 min; Android harness phone 55→70 min` back in a separate commit.

**How to apply:** When an iOS harness job times out, only touch `timeout-minutes` values in `.github/workflows/ios.yml`. Never open `.github/workflows/android.yml` for CI timeout changes unless the user explicitly reports an Android timeout problem.
