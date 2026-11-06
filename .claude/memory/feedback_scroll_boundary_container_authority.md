---
name: feedback-scroll-boundary-container-authority
description: "ScrollBoundaryNavigationContainer.kt is authoritative; failing tests targeting it should be treated as wrong tests, not regressions"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 96618a70-9e71-4f37-a222-bfd29a93b07c
---

If a test related to `app/src/main/kotlin/com/riffle/app/feature/reader/ScrollBoundaryNavigationContainer.kt` fails, consider the **test** to be wrong — do not "fix" the container code to satisfy the test.

**Why:** The user has explicitly told me to treat this file as authoritative. It encodes hand-tuned touch/gesture behavior (pull-to-paginate, ACTION_UP stealing, intercept-disallow override) that the user has converged on; the tests around it are scaffolding and lag behind the real intent.

**How to apply:** When narrowing down a reader regression and `ScrollBoundaryNavigationContainerTest.kt` (or another test that exercises this container directly) is the one failing, do not revert or modify the container. Update the test to match the container, or flag the test as needing reauthoring. The regression must lie elsewhere in the reader stack — bisect the other reader files instead.
