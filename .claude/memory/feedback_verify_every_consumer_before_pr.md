---
name: feedback-verify-every-consumer-before-pr
description: "When adding a capability, drive EVERY existing consumer surface on device before opening the PR — architectural seams don't guarantee every reader/screen is wired"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 23671580-abde-44cc-9a94-aaa5a9e1523a
---

When implementing a new capability that plugs into an existing seam (Catalog capabilities, ProgressPeerCapability, ReaderSync, etc.), unit tests passing is NOT enough. Green JVM tests only prove the code path I wrote is internally consistent — they cannot see:

- Consumers that don't inject the seam at all (CbzReaderViewModel had NO ProgressSyncController wired — invisible to unit tests since the whole missing wiring has no code)
- Payload-format mismatches between what the reader persists and what the server accepts (Komga reader stored Readium Locator JSON but my push expected a bare int)
- Silent-swallow anti-patterns in surrounding code (`catch (_) { emptyList() }`) that hide real errors
- Database limits that only trigger at scale (SQLite 999-var limit only bites at 1000+ rows, ABS libraries are smaller)
- DAO update-preservation semantics that make refresh code look correct but no-op in practice (updateMetadata preserves readingProgress silently)

**Why:** This exact pattern happened in #528 — the initial PR passed all JVM tests and was correct at the ADR level, but needed 5 follow-up commits after opening because every consumer surface had a different missing wire. Each bug was individually simple but only visible under real device drive-through.

**How to apply:** Before opening a PR that touches Catalog capabilities, ProgressSync, reader lifecycle, or any cross-cutting seam:
1. Build APK and install on my AVD.
2. Drive EVERY existing consumer surface end-to-end: for a source-side capability, that means every reader mode (EPUB, PDF, CBZ), every library (Books, Comics, Magazines etc.), and the round-trips (open → advance → close → reopen; install → browse → return; mark-read / mark-unread). Watch server side via curl.
3. `grep` for every ViewModel / repository that OTHER capabilities inject, to see if the new capability needs to be wired the same way.
4. `grep` for `catch (_: Throwable)` and similar silent-swallow patterns in the code paths I touch — either surface the error via logger.w or make it fail loudly. Do this on the way in, not on the way out after users report it.
5. Check the DAO methods I call for update-preservation semantics — `updateMetadata` and cousins often intentionally skip fields; look at the KDoc.

Bundling many slices into one PR amplifies this — smaller, incremental PRs surface missing wires one at a time.

Related: [[feedback_verify_on_device_before_done]], [[feedback_verify_in_avd_before_done]].
