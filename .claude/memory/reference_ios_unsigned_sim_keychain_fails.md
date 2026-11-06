---
name: ios-unsigned-sim-keychain-fails
description: "xcodebuild with CODE_SIGNING_ALLOWED=NO leaves the iOS app without an application-identifier entitlement, so every Keychain SecItemAdd fails silently and IosTokenStorage never persists tokens — library home stays empty after adding a source"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 7cf17588-a5d2-4084-9231-bfe028665ad4
  modified: 2026-09-17T05:39:06.952Z
---

Diagnosed 2026-09-17 on PR #1043 (iOS harness). Symptom: add-source flow succeeds (login + /api/libraries
hit the stub), library home renders "Test Library" but zero items; only two HTTP requests ever leave the app.
Cause: `IosLibraryRefresherImpl.refreshLibraryItems` returns `NoActiveServer` when `tokenStorage.getToken()`
is null. `IosTokenStorage.saveKeychainItem` ignores the `SecItemAdd` status, and under
`CODE_SIGNING_ALLOWED=NO` the simulator app has no application-identifier entitlement → errSecMissingEntitlement.
Verify by reading the sim keychain: `sqlite3 ~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Library/Keychains/keychain-2-debug.db "select agrp,count(*) from genp group by agrp"` — a healthy run shows `FAKETEAMID.com.riffle.iosApp`.

**How to apply:** never pass `CODE_SIGNING_ALLOWED=NO` to `xcodebuild test` for the harness scheme; ad-hoc
"Sign to Run Locally" works with an empty DEVELOPMENT_TEAM on the simulator. Follow-up product bug: Keychain
write failures are swallowed (commit saves token before the try block) — user sees an empty library instead of
"Couldn't save source". Related: [[compose-ios-ime-inset-clips-nonscrollable-forms]].
