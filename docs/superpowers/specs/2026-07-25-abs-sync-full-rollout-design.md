# ABS Sync Full Rollout & WebDAV Future Direction

**Date:** 2026-07-25
**Branch:** pkmetski/enable-abs-sync-no-webdav

## Overview

Remove the username allow-list gate from ABS bookmark annotation sync, making the feature available to all ABS users. Wire a `CHANGELOG.md` into the GitHub Release workflow so the announcement is prominent at the top of the release body. Record the future direction: WebDAV sync becomes Komga-only in a future release.

## Part 1 — Remove the user gate

**File:** `core/data/src/main/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTargetFactory.kt`

Remove:
- The early-return check `if (source.username.trim().lowercase() !in ALLOWED_USERNAMES) return null`
- The `ALLOWED_USERNAMES` companion object

After the change, `AbsBookmarkAnnotationSyncTargetFactory.create()` proceeds to build an `AbsBookmarkAnnotationSyncTarget` for any valid ABS source (correct type, non-null `absUserId`, non-null token) regardless of username.

No other code changes are needed — `AnnotationSyncTargetHolder.buildTarget()` already fans out to all sources the factory accepts.

## Part 2 — Release announcement via CHANGELOG.md

**New file:** `CHANGELOG.md` at the repo root.

Structure: a `## Unreleased` section (updated to the version number at release time is out of scope — the file is hand-maintained) with a prominent notice that ABS sync is now enabled for all users. Subsequent releases prepend new entries above previous ones.

**Release workflow change:** `release.yml` — add `body_path: CHANGELOG.md` to the `softprops/action-gh-release` step. This prepends the CHANGELOG content above the auto-generated commit notes in the GitHub Release body. The existing `generate_release_notes: true` is kept so commit-level detail still appears below.

The fastlane `<versionCode>.txt` step in the workflow is left as-is (it already just writes a pointer URL; F-Droid is not currently a distribution channel).

## Part 3 — Future direction (no code)

**ADR 0047** (`docs/adr/0047-abs-bookmarks-annotation-sync.md`) gets a "Future work" section:

- WebDAV annotation sync will become exclusively for Komga sources in a future release.
- Settings will make this explicit: ABS source entries will not offer a WebDAV configuration option; the WebDAV field will only appear under Komga source entries.
- The `CompositeAnnotationSyncTarget` dual-write path and the WebDAV namespace-stepping logic in `AnnotationSyncTargetHolder` can be simplified once ABS users no longer have a WebDAV child.

## Part 4 — Housekeeping

**README.md:** Remove the Google Play Store and F-Droid rows from the Distribution table. Only GitHub Releases (CI-built signed APK) remains.

**Memory:** Delete the GitHub PR incident entry (`reference_github_pr_incident_2026_07_24.md`). Add a project memory noting that Play Store and F-Droid are not in the distribution plan.

## What is not in scope

- Removing the fastlane changelog infrastructure from the release workflow.
- Any Settings UI changes for the Komga-only WebDAV future (that is a separate feature).
- Simplifying `CompositeAnnotationSyncTarget` or `AnnotationSyncTargetHolder` now that the gate is gone (no dual-write is removed; ABS sources that previously fell through to WebDAV-only now get an ABS child, which is the intended outcome).
