---
name: reference-flaky-replaceall-emission-tests
description: "core:database DAO replaceAllForLibrary_neverEmitsEmptyIntermediateState tests WERE flaky (Room Flow vs runTest race) — fixed to wait deterministically on branch pkmetski/fix-migration-schemas-ci"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 3247576c-4d8c-413a-8e06-2b0ba1bb72fd
---

The three `replaceAllForLibrary_neverEmitsEmptyIntermediateState` tests (in `LibraryItemDaoTest`,
`SeriesDaoTest`, `CollectionDaoTest`) used to flake on a headless AVD with
`java.util.NoSuchElementException: List is empty` at `emittedStates.last()`.

**Cause:** they did `launch { roomFlow.collect { emittedStates.add(it) } }` then `yield()` inside
`runTest`. Room emits on its own query-executor thread, not the test dispatcher, so under virtual
time the emissions raced the assertions.

**Fixed (2026-06-09, branch `pkmetski/fix-migration-schemas-ci`):** rewritten to `runBlocking` +
`launch(Dispatchers.IO)` collecting into a `CopyOnWriteArrayList`, then `withTimeout(5_000)`
poll-waits for the initial emission and for the terminal state before asserting. Verified
deterministic (stress-ran 4× green after previously failing 3/2/3). Do NOT mark these as
expected-flaky anymore — per [[feedback_no_ignoring_failing_tests]] a flake must be fixed, not
tolerated. Related: [[reference_migration_tests_api25_preexisting_fails]].
