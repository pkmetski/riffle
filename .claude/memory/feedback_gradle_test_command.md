---
name: feedback-gradle-test-command
description: Use ./gradlew test jvmTest (not per-module :testDebugUnitTest) to mirror CI before claiming tests are green — KMP modules only run via jvmTest
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 84d14da6-2944-4539-8a1f-c26e2e209a19
  modified: 2026-08-11T11:35:29.699Z
---

Before claiming unit tests are "green" or pushing, run `./gradlew test jvmTest` — the same command CI runs (since the 2026-08 false-green fix, see [[reference-ci-false-green-2026-08]]). `test` sweeps every module's `:test` target; `jvmTest` covers the KMP modules (core:common/models/domain/sources/net), which have NO `test` lifecycle task — `./gradlew test` alone silently skips all ~827 of their tests.

**Why:** I once said "all unit tests green" after running `:core:network:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:database:testDebugUnitTest`, missed `:core:domain:test`, and CI failed on a `ReadingSessionRepository` fake that didn't implement a newly-added interface method. The user was understandably frustrated.

**How to apply:** When adding methods to a domain interface, fakes can live in any module's test sources. `./gradlew test` catches them all. Use this as the pre-push / pre-claim verification step, not module-targeted tasks.
