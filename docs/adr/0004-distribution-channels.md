# ADR 0004 — Distribution via Play Store, F-Droid, and Codeberg Releases

**Status:** Accepted

## Context

The target audience (self-hosted ABS users) skews toward privacy-conscious users who may prefer F-Droid over Google Play. Direct APK distribution via Codeberg Releases serves users who want neither store.

## Decision

Distribute through all three channels:
- **Google Play Store** — broadest Android reach
- **F-Droid** — open-source store, preferred by self-hosters
- **Codeberg Releases** — direct APK sideload

## Consequences

- No proprietary SDKs (Firebase Analytics, Crashlytics, etc.) permitted in the codebase. Open-source alternatives only.
- Builds must be reproducible for F-Droid compatibility.
- CI pipeline must produce a signed APK artifact for Codeberg Releases alongside Play Store and F-Droid builds.
