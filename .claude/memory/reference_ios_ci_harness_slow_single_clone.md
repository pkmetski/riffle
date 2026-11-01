---
name: ios-ci-harness-slow-single-clone
description: iOS harness job 2x slower / timing out = Clone 2 failed to launch xctrunner so the suite ran serially on Clone 1; root cause was Xcode 16.4 tests on an iOS 26.2 simulator runtime (newest-runtime selection) + 3 booted sims on 3 cores
metadata: 
  node_type: memory
  type: reference
  originSessionId: 7cf17588-a5d2-4084-9231-bfe028665ad4
  modified: 2026-09-17T10:50:40.063Z
---

Diagnosed 2026-09-17 on PR #1043. Compare runs by parsing the `ios-harness-log` artifact:
`Test case '...' passed on 'Clone N of iPhone ...' (Ns)` → sum seconds per clone.
- Fast run: Clone 1 603 s + Clone 2 566 s, testing elapsed 914 s, job 25 min.
- Slow run: Clone 1 1473 s, Clone 2 nothing, log ends with `Failed to launch app with identifier:
  com.riffle.iosAppTests.xctrunner` for Clone 2 → elapsed 1686 s, job 43 min (limit 50).
- Timeout run: `Launch Riffle Finished with error: Failed to send signal 19` right after the build,
  then silence until the 50-min kill; zero tests.
Note: with `-parallel-testing-enabled YES` xcodebuild prints per-test lines only at the very end —
a quiet log after the build is normal, not a hang by itself.

Root causes: (1) the selection script took the newest runtime on the macos-15 image → iOS 26.2
"iPhone 17 Pro" under Xcode 16.4 (SDK 18.5); (2) the pre-booted warm-up device stayed booted next
to the two clones (3 sims / 3 cores). Fix in ios.yml: prefer the runtime matching
`xcrun --sdk iphonesimulator --show-sdk-version` (then same major, then newest) and
`simctl shutdown` the warm-up device before xcodebuild. Related: [[ios-xcode-stale-framework-and-kn-gotchas]],
[[ios-unsigned-sim-keychain-fails]].

**How to apply:** when an iOS harness job is slow or times out, first check clone distribution and
grep for `Failed to launch app` / `Failed to send signal` before suspecting the tests.
