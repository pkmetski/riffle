---
name: feedback_no_ios_scenarios_docs
description: Never create docs/testing/ios-scenarios/ — user explicitly removed it and does not want it recreated
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 473c4644-5de3-4b5b-808d-56be7d2ff3e0
  modified: 2026-09-15T08:06:17.275Z
---

Never create files under `docs/testing/ios-scenarios/` or any markdown scenario docs as a substitute for real iOS tests.

**Why:** The user deleted this directory and explicitly said they want actual XCTest tests — not documentation. Scenario docs are not a substitute for test code, same rule as in AGENTS.md for Android.

**How to apply:** iOS coverage means real `XCTest` functions in `iosApp/iosAppTests/`. Never write markdown files describing test scenarios instead of writing the test. Do not create any `docs/testing/ios-scenarios/` files.
