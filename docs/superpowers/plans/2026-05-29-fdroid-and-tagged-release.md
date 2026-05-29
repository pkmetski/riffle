# F-Droid Integration & Tag-Driven Release — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `git tag vX.Y.Z` into a signed APK on a GitHub Release plus an updated F-Droid changelog, with `versionName` and `versionCode` derived from the tag.

**Architecture:** Tag-driven: workflow parses `GITHUB_REF_NAME`, passes `-PversionName` and `-PversionCode` to gradle, gradle's `signingConfigs.release` reads env vars exported by the workflow, then the workflow appends a fastlane changelog file and pushes it back to `main`. F-Droid metadata scaffold sits in the repo for the manual one-time `fdroiddata` MR.

**Tech Stack:** GitHub Actions, Gradle Kotlin DSL, Android `signingConfigs`, fastlane metadata layout.

Spec: `docs/superpowers/specs/2026-05-29-fdroid-and-tagged-release-design.md`

---

### Task 1: Wire `signingConfigs.release` and tag-driven `versionCode` into `app/build.gradle.kts`

**Files:**
- Modify: `app/build.gradle.kts` (lines 21–75)

- [ ] **Step 1: Edit `defaultConfig` to read `versionCode` from a gradle property**

Replace `versionCode = 1` at `app/build.gradle.kts:29` with:
```kotlin
versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
```

- [ ] **Step 2: Add `signingConfigs.release` reading from env vars, guarded by `KEYSTORE_PATH`**

Insert directly after the `defaultConfig { ... }` block closes (around line 41) and before `buildTypes`:
```kotlin
    val keystorePath = System.getenv("KEYSTORE_PATH")
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
```

- [ ] **Step 3: Wire the signingConfig in `buildTypes.release`**

In the `release { ... }` block (around line 48), add at the top:
```kotlin
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
```

- [ ] **Step 4: Verify gradle still configures locally (no keystore present)**

Run: `./gradlew :app:tasks --quiet | head -5`
Expected: exits 0, no `KEYSTORE_PATH` error.

- [ ] **Step 5: Verify `-PversionName` + `-PversionCode` propagate to `BuildConfig`**

Run: `./gradlew :app:assembleDebug -PversionName=9.9.9 -PversionCode=999`
Expected: build succeeds.

Then inspect the generated BuildConfig:
```bash
grep -E 'VERSION_(NAME|CODE)' app/build/generated/source/buildConfig/debug/com/riffle/app/BuildConfig.java
```
Expected: `VERSION_NAME = "9.9.9"` and `VERSION_CODE = 999`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: wire signingConfig.release and tag-driven versionCode"
```

---

### Task 2: Update `release.yml` to derive version from tag, sign, and verify

**Files:**
- Modify: `.github/workflows/release.yml` (full rewrite of the build step section)

- [ ] **Step 1: Replace the workflow with the tag-driven version**

Replace `.github/workflows/release.yml` contents with:
```yaml
name: Release

on:
  push:
    tags:
      - "v*"

permissions:
  contents: write

jobs:
  release:
    name: Build Signed APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Setup Java
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 17

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v6
        with:
          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}

      - name: Download Gradle wrapper jar
        run: |
          curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
            "https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar"

      - name: Download fonts
        run: make fonts

      - name: Compute version from tag
        id: ver
        run: |
          NAME="${GITHUB_REF_NAME#v}"
          if ! [[ "$NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "::error::Tag '$GITHUB_REF_NAME' is not vMAJOR.MINOR.PATCH" >&2
            exit 1
          fi
          IFS=. read -r MAJ MIN PAT <<<"$NAME"
          CODE=$((10#$MAJ * 10000 + 10#$MIN * 100 + 10#$PAT))
          echo "name=$NAME" >>"$GITHUB_OUTPUT"
          echo "code=$CODE" >>"$GITHUB_OUTPUT"

      - name: Decode keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          echo "$KEYSTORE_BASE64" | base64 --decode > release.jks

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

      - name: Verify APK is signed
        run: |
          APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
          "$ANDROID_HOME"/build-tools/*/apksigner verify --verbose "$APK"

      - name: Write F-Droid changelog
        env:
          NAME: ${{ steps.ver.outputs.name }}
          CODE: ${{ steps.ver.outputs.code }}
        run: |
          DIR=fastlane/metadata/android/en-US/changelogs
          mkdir -p "$DIR"
          echo "Release v$NAME — see https://github.com/${{ github.repository }}/releases/tag/v$NAME" > "$DIR/$CODE.txt"

      - name: Commit changelog back to main
        run: |
          git config user.name 'github-actions[bot]'
          git config user.email 'github-actions[bot]@users.noreply.github.com'
          git fetch origin main
          git checkout main
          git add fastlane/metadata/android/en-US/changelogs/${{ steps.ver.outputs.code }}.txt
          git commit -m "chore(fdroid): changelog for v${{ steps.ver.outputs.name }}" || echo "no change"
          git push origin main

      - name: Upload APK to GitHub Release
        uses: softprops/action-gh-release@v3
        with:
          files: app/build/outputs/apk/release/*.apk
          generate_release_notes: true

      - name: Cleanup keystore
        if: always()
        run: rm -f release.jks
```

- [ ] **Step 2: Lint the YAML locally**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml'))" && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): derive version from tag, sign APK, write F-Droid changelog"
```

---

### Task 3: Create the F-Droid fastlane metadata scaffold

**Files:**
- Create: `fastlane/metadata/android/en-US/title.txt`
- Create: `fastlane/metadata/android/en-US/short_description.txt`
- Create: `fastlane/metadata/android/en-US/full_description.txt`
- Create: `fastlane/metadata/android/en-US/changelogs/.gitkeep`
- Create: `fastlane/metadata/android/en-US/images/.gitkeep`

- [ ] **Step 1: Write `title.txt`**

```
Riffle
```

- [ ] **Step 2: Write `short_description.txt`** (≤80 chars)

```
A Readium-based EPUB and PDF reader that syncs with Audiobookshelf libraries.
```

- [ ] **Step 3: Write `full_description.txt`**

```
Riffle is an Android reader for EPUB and PDF books served by an Audiobookshelf instance.

Features:
- Connects to your self-hosted Audiobookshelf server and browses its book libraries.
- Streams or downloads books on demand.
- Reading experience powered by Readium, including pagination, themes, and font controls.
- Immersive reading mode with edge-tap and volume-key navigation.
- Per-book reading position is preserved between sessions.

Riffle is open source. Source code, issues, and releases live on GitHub.
```

- [ ] **Step 4: Add `.gitkeep` placeholders for image and changelog directories**

Both files contain a single empty line.

- [ ] **Step 5: Verify directory layout**

Run: `find fastlane -type f | sort`
Expected:
```
fastlane/metadata/android/en-US/changelogs/.gitkeep
fastlane/metadata/android/en-US/full_description.txt
fastlane/metadata/android/en-US/images/.gitkeep
fastlane/metadata/android/en-US/short_description.txt
fastlane/metadata/android/en-US/title.txt
```

- [ ] **Step 6: Commit**

```bash
git add fastlane/
git commit -m "fdroid: add fastlane metadata scaffold"
```

---

### Task 4: Document the one-time `fdroiddata` submission

**Files:**
- Create: `docs/fdroid-submission.md`

- [ ] **Step 1: Write the submission doc**

```markdown
# Submitting Riffle to F-Droid

This is a one-time manual step. After it lands, `AutoUpdateMode: Version` in the
recipe causes F-Droid to pick up every subsequent `vX.Y.Z` tag automatically.

## Prerequisites

- A FOSS `LICENSE` file at the repo root. (F-Droid will not accept an app without one.)
- At least one signed tag pushed (CI green, APK attached to the GitHub Release).
- A GitLab account.

## Steps

1. Fork https://gitlab.com/fdroid/fdroiddata
2. Create `metadata/com.riffle.app.yml` with:

   ```yaml
   Categories:
     - Reading
   License: <SPDX id matching repo LICENSE>
   AuthorName: pkmetski
   SourceCode: https://github.com/pkmetski/riffle
   IssueTracker: https://github.com/pkmetski/riffle/issues

   AutoName: Riffle

   RepoType: git
   Repo: https://github.com/pkmetski/riffle.git

   Builds:
     - versionName: <X.Y.Z of first tag>
       versionCode: <NNN matching that tag>
       commit: v<X.Y.Z>
       subdir: app
       gradle:
         - yes
       prebuild:
         - make fonts

   AutoUpdateMode: Version
   UpdateCheckMode: Tags ^v[0-9.]+$
   CurrentVersion: <X.Y.Z>
   CurrentVersionCode: <NNN>
   ```

3. Open a merge request against `fdroiddata`. Address reviewer feedback (typical: lint nits, license clarification).
4. After merge, monitor https://f-droid.org/packages/com.riffle.app/ — the build server picks it up on its next scan.
```

- [ ] **Step 2: Commit**

```bash
git add docs/fdroid-submission.md
git commit -m "docs: F-Droid submission runbook"
```

---

### Task 5: Mark feature complete in README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Locate the Features list entry for F-Droid / release builds**

Run: `grep -nE 'F-?Droid|signed APK|tagged release' README.md` and identify the matching `- [ ]` line(s).

- [ ] **Step 2: Flip `- [ ]` to `- [x]` for the matching line(s)**

If no matching entry exists, skip this task — the README didn't track this feature.

- [ ] **Step 3: Commit if a change was made**

```bash
git add README.md
git commit -m "docs: mark F-Droid integration complete in README"
```

---

## Self-Review Notes

- **Spec coverage:**
  - signingConfig + env-var guard → Task 1 ✅
  - versionCode/versionName from tag → Task 1 (gradle) + Task 2 (workflow) ✅
  - release.yml updates with apksigner verify + changelog write-back → Task 2 ✅
  - Fastlane scaffold → Task 3 ✅
  - fdroiddata one-time submission documented → Task 4 ✅
  - In-app burger-menu version: already wired (`NavigationDrawerComposable.kt:105`), no task needed (called out in spec §"In-app version display") ✅
  - LICENSE: spec flagged as Open Question; not in scope of this plan (Task 4 documents the dependency) ✅
- **Placeholders:** none — every code/yaml block is complete.
- **Type/name consistency:** `KEYSTORE_PATH`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD` used identically across `build.gradle.kts` and `release.yml`. `versionName` / `versionCode` gradle property names match across gradle, workflow, and fdroiddata recipe.
