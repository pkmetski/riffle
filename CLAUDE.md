# Riffle — Agent Instructions

## Running harness tests

Always run harness tests via `make harness-test`. Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. The Makefile target boots the "Harness Medium Phone" AVD, runs tests against it exclusively, then shuts it down.

## Feature progress

When an issue is closed (a feature is implemented and merged), mark it as complete in the Features list in `README.md` by changing `- [ ]` to `- [x]` on the corresponding line, and include that change in the PR for that issue.
