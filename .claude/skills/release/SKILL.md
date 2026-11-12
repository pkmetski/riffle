---
name: release
description: Cut a new Riffle release — create and push a vMAJOR.MINOR.PATCH[-SUFFIX] git tag, which triggers the release workflow to build the signed APK and publish a GitHub Release with generated notes. Use when the user wants to release, cut a release, publish a version, or ship a tag like "v2.5.1" or "v2.6.0-rc1".
---

# Release

Riffle releases are tag-driven. Pushing a `vX.Y.Z` (or `vX.Y.Z-SUFFIX` pre-release) tag fires
`.github/workflows/release.yml`, which builds the signed APK, generates release notes, and
publishes the GitHub Release. The skill's job is to create and push the right tag — the
workflow does the rest.

## Input

An optional version tag override, e.g. `v2.5.1` or `v2.6.0-rc1`. If omitted, the skill computes
the next stable version automatically (see step 3). If provided without a leading `v`, add it.

Pre-release tags (`vX.Y.Z-SUFFIX`, e.g. `v2.6.0-rc1`, `v2.6.0-beta.1`) must be supplied
explicitly — auto-bump never produces them. Suffix allowed chars: `[0-9A-Za-z.-]`.

## Workflow

1. **Resolve the target commit from the remote.** Releases always ship the latest commit on
   `origin/main` — never the local branch or local working changes. Fetch and read the remote tip:
   ```sh
   git fetch origin --tags
   git rev-parse origin/main
   ```
   Do **not** push local commits, do **not** check out or rely on the local branch, and do **not**
   include any uncommitted local changes. The tag points at `origin/main`'s tip, whatever it is.

2. **Find the previous release tag.**
   ```sh
   git describe --tags --abbrev=0 origin/main   # e.g. v2.8.2
   ```

3. **Compute the next version** (skip if the user supplied an explicit tag).

   List commits since the previous tag:
   ```sh
   git log <prev-tag>..origin/main --oneline
   ```
   Classify each subject line:
   - Any subject matching `^feat(\(.+\))?(!)?:` → **minor** bump
   - Everything else (`fix`, `refactor`, `chore`, `docs`, etc.) → **patch** bump
   - Take the highest classification across all commits (minor beats patch).
   - **Never auto-bump MAJOR** — that is always a manual decision by the user.

   Increment the right segment of the previous tag:
   - minor bump: `vX.Y.Z` → `vX.(Y+1).0`
   - patch bump: `vX.Y.Z` → `vX.Y.(Z+1)`

   Present the proposed tag and the reasoning (e.g. *"1 `feat` commit → minor bump → `v2.9.0`"*)
   and ask the user to confirm or override before proceeding. A MAJOR override (e.g. `v3.0.0`) is
   always accepted — just validate the format.

4. **Validate the tag.** Must match `^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$` (the workflow
   rejects anything else). Check it doesn't already exist: `git tag -l <tag>` and
   `gh release view <tag>`.

   If the tag has a `-SUFFIX`, tell the user this will publish a **pre-release**: the GitHub
   Release is flagged as pre-release, and the in-app auto-updater (which filters out prereleases)
   will NOT offer it to stable users.

5. **Create and push only the tag** (once the user confirms):
   ```sh
   git tag -a <tag> origin/main -m "<tag>"
   git push origin <tag>
   ```

## Notes

- Do **not** run `gh release create` — the workflow publishes the release (with the signed APK and
  generated notes). Creating it manually first would publish an APK-less release.
- If something goes wrong before the workflow runs, an unpushed tag is just local: `git tag -d <tag>`.
  Once pushed, deleting needs `git push origin :<tag>` and removing any created release.
