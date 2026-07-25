# ABS Sync Full Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the username allow-list gate from ABS bookmark sync, add a prominent release announcement, update the ADR with future direction, and clean up the README.

**Architecture:** Delete two code blocks in the factory (the guard check and the companion object), replace the gate-specific tests with a positive any-username test, wire `CHANGELOG.md` into the GitHub Release workflow, and add documentation updates in the ADR and README.

**Tech Stack:** Kotlin, JUnit 4 (runTest), GitHub Actions (`softprops/action-gh-release@v3`)

## Global Constraints

- Do not touch the factory's other null-return guards (non-ABS type, missing absUserId, missing token) — only the username check is removed.
- Do not rename, reorganise, or reformat files beyond what each task requires.
- Commit message format: `type(scope): description` (Conventional Commits).
- No `Co-Authored-By` or "Generated with Claude Code" trailers in any commit.

---

### Task 1: Remove the rollout gate from the factory

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTargetFactory.kt`
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTargetFactoryTest.kt`

**Interfaces:**
- Produces: `AbsBookmarkAnnotationSyncTargetFactory.create(source)` now returns non-null for any ABS source with a valid `absUserId` and stored token, regardless of username.

- [ ] **Step 1: Remove the username guard from the factory**

In `AbsBookmarkAnnotationSyncTargetFactory.kt`, delete lines 24–28 (the comment block + the guard check) and lines 41–43 (the `private companion object` containing `ALLOWED_USERNAMES`). The file should look like this after the edit:

```kotlin
package com.riffle.core.data.absbookmark

import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsBookmarkApi
import javax.inject.Inject

/**
 * Builds an [AbsBookmarkAnnotationSyncTarget] for a single ABS [Source].
 *
 * Returns null when the source is ineligible (not ABS, missing token, missing `absUserId`) so the
 * holder can quietly skip it — the same source may become eligible later after the user re-auths.
 */
class AbsBookmarkAnnotationSyncTargetFactory @Inject constructor(
    private val absBookmarkApi: AbsBookmarkApi,
    private val tokenStorage: TokenStorage,
) {
    suspend fun create(source: Source): AbsBookmarkAnnotationSyncTarget? {
        if (source.type != SourceType.ABS) return null
        if (source.serverType != ServerType.AUDIOBOOKSHELF) return null
        val absUserId = source.absUserId?.takeIf { it.isNotBlank() } ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        val namespace = "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}$absUserId"
        return AbsBookmarkAnnotationSyncTarget(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
            accountNamespace = namespace,
            api = absBookmarkApi,
        )
    }
}
```

- [ ] **Step 2: Update the factory tests**

Replace `AbsBookmarkAnnotationSyncTargetFactoryTest.kt` with the updated test class below. The four gate-specific tests (`allow-listed username 'plamen'`, `allow-listed username 'test'`, `null for other usernames`, `case-insensitive and trims whitespace`) are removed and replaced by a single positive test confirming any username works. The three eligibility tests (non-ABS type, missing absUserId, missing token) are unchanged.

```kotlin
package com.riffle.core.data.absbookmark

import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AbsBookmarkAnnotationSyncTargetFactoryTest {

    private val tokenStorage = InMemoryTokenStorage().apply {
        savedTokens["source-1"] = "token-abc"
    }
    private val api = NoopApi
    private val factory = AbsBookmarkAnnotationSyncTargetFactory(api, tokenStorage)

    private fun source(
        username: String,
        type: SourceType = SourceType.ABS,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
        absUserId: String? = "abs-user-1",
        id: String = "source-1",
    ) = Source(
        id = id,
        url = SourceUrl.parse("http://abs.local")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = username,
        type = type,
        serverType = serverType,
        absUserId = absUserId,
    )

    @Test
    fun `create returns a target for any valid ABS username`() = runTest {
        assertNotNull(factory.create(source(username = "alice")))
        assertNotNull(factory.create(source(username = "bob")))
        assertNotNull(factory.create(source(username = "")))
        assertNotNull(factory.create(source(username = "plamen")))
        assertNotNull(factory.create(source(username = "test")))
    }

    @Test
    fun `create returns null for non-ABS source`() = runTest {
        assertNull(factory.create(source(username = "alice", type = SourceType.LOCAL_FILES)))
    }

    @Test
    fun `create returns null when absUserId is missing`() = runTest {
        assertNull(factory.create(source(username = "alice", absUserId = null)))
        assertNull(factory.create(source(username = "alice", absUserId = "  ")))
    }

    @Test
    fun `create returns null when the source has no stored token`() = runTest {
        assertNull(factory.create(source(username = "alice", id = "no-token-source")))
    }
}

private class InMemoryTokenStorage : TokenStorage {
    val savedTokens: MutableMap<String, String> = mutableMapOf()
    override suspend fun saveToken(sourceId: String, token: String) { savedTokens[sourceId] = token }
    override suspend fun getToken(sourceId: String): String? = savedTokens[sourceId]
    override suspend fun deleteToken(sourceId: String) { savedTokens.remove(sourceId) }
}

private object NoopApi : AbsBookmarkApi {
    override suspend fun createBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun updateBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun deleteBookmark(
        baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))
    override suspend fun listBookmarks(
        baseUrl: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> = NetworkResult.Success(emptyList())
}
```

- [ ] **Step 3: Run the factory tests**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:data:test --tests "com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactoryTest" 2>&1 | tail -20
```

Expected: all 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTargetFactory.kt \
        core/data/src/test/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTargetFactoryTest.kt
git commit -m "feat(sync): enable ABS bookmark sync for all users, remove username allow-list"
```

---

### Task 2: Create CHANGELOG.md and wire it into the release workflow

**Files:**
- Create: `CHANGELOG.md`
- Modify: `.github/workflows/release.yml` (add `body_path` to the upload step)

**Interfaces:**
- Consumes: nothing from Task 1 — independent.
- Produces: `CHANGELOG.md` at the repo root; every GitHub Release body will begin with its contents.

- [ ] **Step 1: Create CHANGELOG.md**

Create `CHANGELOG.md` at the repo root with the following content (use the Write tool — no tests needed for a markdown file):

```markdown
# Changelog

Major announcements and highlights for each release. Full commit-level detail is auto-generated below by GitHub.

---

## Unreleased

### Annotation sync now works for all Audiobookshelf users — no WebDAV required

Riffle now syncs highlights, bookmarks, and notes across your devices automatically for every Audiobookshelf user. No WebDAV server setup needed.

Previously this feature was only available to a small set of test accounts. Starting with this release it is fully enabled. Your annotations are stored as bookmarks on your ABS server and stay private to your account.

**What this means:**
- If you use Audiobookshelf, annotation sync is on by default the next time you open the app.
- If you already had WebDAV configured, it continues to work alongside ABS sync. Nothing is lost.
- If you use Komga, WebDAV sync is unchanged.
```

- [ ] **Step 2: Add `body_path` to the release upload step in `release.yml`**

In `.github/workflows/release.yml`, locate the `Upload APK to GitHub Release` step (currently lines 121–126) and add `body_path: CHANGELOG.md`:

```yaml
      - name: Upload APK to GitHub Release
        uses: softprops/action-gh-release@v3
        with:
          files: app/build/outputs/apk/release/*.apk
          body_path: CHANGELOG.md
          generate_release_notes: true
          prerelease: ${{ steps.ver.outputs.prerelease == 'true' }}
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md .github/workflows/release.yml
git commit -m "chore(release): add CHANGELOG.md and wire it into GitHub Release body"
```

---

### Task 3: Update ADR 0047 with future direction

**Files:**
- Modify: `docs/adr/0047-abs-bookmarks-annotation-sync.md`

**Interfaces:**
- Consumes: nothing from prior tasks — independent.
- Produces: ADR updated with (a) rollout gate removed note, (b) future work section for Komga-only WebDAV.

- [ ] **Step 1: Update the ADR status and add a Future Work section**

At the top of `docs/adr/0047-abs-bookmarks-annotation-sync.md`, change the status line from:

```
**Status:** Accepted 2026-07-19
```

to:

```
**Status:** Accepted 2026-07-19 · Gate removed 2026-07-25
```

At the end of the file, after the `## References` section, append:

```markdown

## Future work

### WebDAV becomes exclusively for Komga (future release)

ABS users do not need WebDAV for annotation sync. In a future release:

- The annotation sync Settings screen will only show the WebDAV configuration option when the user has at least one Komga source. ABS-only users will see no WebDAV field.
- The `CompositeAnnotationSyncTarget` dual-write path and the namespace-filtering predicate in `AnnotationSyncTargetHolder.buildTarget()` can be simplified: if no WebDAV child is ever created for ABS sources, the composite is never needed for ABS-only accounts.
- A new ADR will record the Settings UI change and the simplified holder logic.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0047-abs-bookmarks-annotation-sync.md
git commit -m "docs(adr): record gate removal and future Komga-only WebDAV direction in ADR 0047"
```

---

### Task 4: Clean up README and update memory

**Files:**
- Modify: `README.md`
- Modify/Delete: memory files under `~/.claude/projects/-Users-plamen-kmetski-dev-riffle/memory/`

**Interfaces:**
- Consumes: nothing from prior tasks — independent.

- [ ] **Step 1: Remove Google Play and F-Droid rows from the README Distribution table**

In `README.md`, the Distribution table currently reads:

```markdown
| Channel | Status |
|---------|--------|
| Google Play Store | Planned |
| F-Droid | Planned |
| GitHub Releases | CI-built signed APK |
```

Replace it with:

```markdown
| Channel | Status |
|---------|--------|
| GitHub Releases | CI-built signed APK |
```

- [ ] **Step 2: Commit the README change**

```bash
git add README.md
git commit -m "docs: remove Google Play and F-Droid from distribution table (not planned)"
```

- [ ] **Step 3: Delete the GitHub PR incident memory file**

Delete `~/.claude/projects/-Users-plamen-kmetski-dev-riffle/memory/reference_github_pr_incident_2026_07_24.md` and remove its line from `MEMORY.md`.

- [ ] **Step 4: Add a distribution memory**

Create `~/.claude/projects/-Users-plamen-kmetski-dev-riffle/memory/project_distribution_channels.md`:

```markdown
---
name: project-distribution-channels
description: Riffle distribution channels — GitHub Releases only; Play Store and F-Droid are NOT planned
metadata:
  type: project
---

Riffle is distributed exclusively via GitHub Releases (CI-built signed APK). Google Play Store and F-Droid are not in the distribution plan.

**Why:** The user removed these from the README on 2026-07-25; they were listed as "Planned" but are not being pursued.

**How to apply:** Do not suggest or reference Play Store or F-Droid submission. The fastlane changelog infrastructure in release.yml is vestigial and can be ignored.
```

Add a line to `MEMORY.md`:

```
- [Distribution channels](project_distribution_channels.md) — GitHub Releases only; Play Store and F-Droid NOT planned
```

Remove the existing line referencing `reference_github_pr_incident_2026_07_24.md` from `MEMORY.md`.
