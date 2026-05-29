# F-Droid Integration & Tag-Driven Release Build

**Status:** Draft
**Date:** 2026-05-29

## Goal

Turn `git tag vX.Y.Z` into a single, reproducible release event that:

1. Produces a **signed release APK** attached to a GitHub Release.
2. Updates **F-Droid fastlane metadata** so the official F-Droid repository (`fdroiddata`) can pick up the new version and build it on F-Droid's build server.
3. Encodes the tag as both `versionName` (e.g. `0.2.3`) and `versionCode` (e.g. `203`) so the same gradle invocation works in CI and on F-Droid's build server.

The tag is the single source of truth for the version. The version is reflected in the in-app burger menu via `BuildConfig.VERSION_NAME` (already wired at `NavigationDrawerComposable.kt:105`).

Non-goals:

- Self-hosted F-Droid repo.
- Automation of the one-time `fdroiddata` PR (manual; documented here).
- Reproducible-builds verification (F-Droid will sign its own copy from source; the GitHub-release APK uses our keystore. The two coexist).

## Source of truth: the tag

Tag format: `vMAJOR.MINOR.PATCH` (semver, no pre-release suffix in v1).

Derived values:

| Property | Derivation | Example for `v0.2.3` |
|---|---|---|
| `versionName` | `${tag#v}` | `0.2.3` |
| `versionCode` | `MAJOR*10000 + MINOR*100 + PATCH` | `203` |

The encoding caps `MINOR` and `PATCH` at `99`, which is acceptable for the foreseeable future. If we ever need pre-releases or more headroom, we revisit (out of scope here).

## Component changes

### 1. `app/build.gradle.kts` — signing + tag-driven version

Add a `signingConfigs.release` block that reads from environment variables already exported by `release.yml`. Wire `buildTypes.release.signingConfig` to it, but only when the keystore env var is present so local debug builds and contributor PRs keep working.

```kotlin
val keystorePath = System.getenv("KEYSTORE_PATH")

android {
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        // versionName already reads -PversionName; add the symmetric versionCode read.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1.0"
        // ...
    }

    buildTypes {
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // existing minify/shrink stays
        }
    }
}
```

Rationale for the env-var guard: a contributor running `./gradlew :app:assembleRelease` locally has no keystore — without the guard, gradle configuration fails. With the guard, local release builds produce an unsigned APK (same as today) and CI produces a signed one.

Debug fallback `versionName = "0.1.0"` stays as-is (user-confirmed).

### 2. `.github/workflows/release.yml` — derive both versions from the tag

Replace the existing single gradle invocation with a step that computes `versionName` and `versionCode` from `GITHUB_REF_NAME`, plus an extra step that writes a changelog file for F-Droid (see §3).

Sketch of the modified gradle step:

```yaml
- name: Compute version from tag
  id: ver
  run: |
    NAME="${GITHUB_REF_NAME#v}"
    IFS=. read -r MAJ MIN PAT <<<"$NAME"
    CODE=$((MAJ*10000 + MIN*100 + PAT))
    echo "name=$NAME" >>"$GITHUB_OUTPUT"
    echo "code=$CODE" >>"$GITHUB_OUTPUT"

- name: Build release APK
  env:
    KEYSTORE_PATH: ${{ github.workspace }}/release.jks
    KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
    STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
  run: |
    ./gradlew :app:assembleRelease \
      -PversionName=${{ steps.ver.outputs.name }} \
      -PversionCode=${{ steps.ver.outputs.code }}
```

The workflow keeps using `softprops/action-gh-release` with `generate_release_notes: true`, so the GitHub Release description is auto-generated from PR titles.

### 3. F-Droid fastlane metadata (committed to this repo)

F-Droid's build server reads metadata from `fastlane/metadata/android/en-US/` in the app's repo. Directory layout to create:

```
fastlane/metadata/android/en-US/
├── title.txt                     # "Riffle"
├── short_description.txt         # one line, <=80 chars
├── full_description.txt          # multi-paragraph, plain text
├── images/
│   ├── icon.png                  # 512x512 PNG
│   └── phoneScreenshots/         # at least 2 PNGs
│       ├── 01-library.png
│       └── 02-reader.png
└── changelogs/
    └── <versionCode>.txt         # one per release; ≤500 chars
```

Per-release changelog: on tag push, the workflow writes `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and commits/pushes it back to `main` so F-Droid sees it on its next scan. Source of the text: the GitHub-generated release notes (or, simpler v1: a placeholder `"Release vX.Y.Z — see https://github.com/pkmetski/riffle/releases/tag/vX.Y.Z"`).

**Open choice (low-stakes):** v1 changelog content.
- (i) **Static placeholder pointing at the GitHub release page.** Zero risk, always works, but minimal info for F-Droid users.
- (ii) **Auto-generated body from `gh release view`.** Richer, but PR titles bleed into the F-Droid changelog UI which has tight character limits and no markdown.

Spec choice: **(i)** for v1. Trivial to change later.

### 4. One-time `fdroiddata` submission (manual)

Out-of-band step the maintainer does once after the first release tag works:

1. Fork `https://gitlab.com/fdroid/fdroiddata`.
2. Add `metadata/com.riffle.app.yml`:

   ```yaml
   Categories:
     - Reading
   License: <project license — TBD, see §Open Questions>
   AuthorName: pkmetski
   SourceCode: https://github.com/pkmetski/riffle
   IssueTracker: https://github.com/pkmetski/riffle/issues

   AutoName: Riffle

   RepoType: git
   Repo: https://github.com/pkmetski/riffle.git

   Builds:
     - versionName: 0.X.Y         # first tag being submitted
       versionCode: NNN
       commit: v0.X.Y
       subdir: app
       gradle:
         - yes
       prebuild:
         - make fonts             # downloads font binaries (same as CI)

   AutoUpdateMode: Version
   UpdateCheckMode: Tags ^v[0-9.]+$
   CurrentVersion: 0.X.Y
   CurrentVersionCode: NNN
   ```

3. Open a merge request against `fdroiddata`. Once accepted, future tags are picked up automatically because `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` watch our git tags.

The recipe must use the same gradle invocation pattern as our CI; the `prebuild: make fonts` step mirrors `release.yml` and is required because fonts are not committed.

## In-app version display

No change required. `NavigationDrawerComposable.kt:105` already reads `BuildConfig.VERSION_NAME`. After this change `versionCode` is also tag-correct in case anything starts to depend on it.

## Testing

- **Unit:** none — gradle configuration changes are exercised by the build itself.
- **CI smoke:** push a throwaway `v0.0.0` tag to a scratch branch in a fork (or a `v0.X.Y-rc` tag), verify the workflow produces a signed APK that installs on a device and the in-app drawer shows the right version. (Note: pre-release tags don't match `v*` if we use `vX.Y.Z`; the smoke test will use a real-shaped tag like `v0.1.1` against a draft release.)
- **`apksigner verify` step in the workflow** as a guard — fails fast if signing regresses.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Tag malformed (`v0.2` not `v0.2.3`) | `Compute version` step fails on empty `$PAT` → workflow fails before building. |
| `versionCode` collision after the manual `1` we shipped before | First tag must be `v0.0.1` or later; `1` is already used. Bump to `v0.0.2` if needed. |
| Lost keystore | Already mitigated: stored in repo secret `RELEASE_KEYSTORE_BASE64` + maintainer's offline backup. |
| F-Droid build fails on our recipe | `fdroiddata` MR review surfaces this; we iterate the YAML before it merges. |

## Open Questions

1. **License.** F-Droid requires a clear FOSS license in the repo (`LICENSE` file). Not in scope to choose here, but the `fdroiddata` PR cannot be opened without it. To resolve before that manual step.
2. **First post-this-change tag.** Current built `versionCode=1`. First new tag should be ≥ `v0.0.2` (code `2`) to keep `versionCode` monotonically increasing. Confirm at tagging time.
