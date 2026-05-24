# Read/Unread Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a toggle button to the Library Item Detail Screen that lets the user manually mark a book as read (sets `readingProgress = 1.0`) or unread (sets `readingProgress = 0.0`), syncing to ABS immediately and falling back to the Progress Sync cycle on failure.

**Architecture:** The toggle updates the local DB via `LibraryRepository.updateReadingProgress`, bumps `localUpdatedAt` in `reading_positions` so the sync cycle will push it on the next tick, and attempts an immediate PATCH via a new `ReadingSessionRepository.setProgress` method. Failure of the PATCH is silently ignored — consistent with offline behaviour. The ViewModel updates `_uiState` optimistically so the icon flips immediately without waiting for a DB observation.

**Tech Stack:** Kotlin, Hilt, Room, OkHttp, Jetpack Compose, `kotlinx.coroutines.test`

---

## File Map

| File | Change |
|---|---|
| `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSessionRepository.kt` | Add `setProgress(itemId, progress)` |
| `core/data/src/main/kotlin/com/riffle/core/data/ReadingSessionRepositoryImpl.kt` | Implement `setProgress` |
| `core/data/src/test/kotlin/com/riffle/core/data/ProgressSyncCycleTest.kt` | Add tests for `setProgress` |
| `app/src/main/kotlin/com/riffle/app/feature/library/ReadToggleButton.kt` | New composable (mirrors `DownloadButton.kt`) |
| `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt` | Inject `ReadingSessionRepository`; add `markAsRead` / `markAsUnread` |
| `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt` | Add `ReadToggleButton` to action row |

---

### Task 1: Add `setProgress` to `ReadingSessionRepository`

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSessionRepository.kt`

- [ ] **Step 1: Add the method to the interface**

Replace the file contents with:

```kotlin
package com.riffle.core.domain

interface ReadingSessionRepository {
    suspend fun syncProgress(itemId: String, payload: SessionPayload): SyncSessionResult
    suspend fun runSyncCycle(itemId: String, payload: SessionPayload): ProgressSyncCycleResult
    suspend fun setProgress(itemId: String, progress: Float)
}
```

- [ ] **Step 2: Verify the project still compiles**

```bash
./gradlew :core:domain:compileDebugKotlin --quiet
```

Expected: `BUILD SUCCESSFUL`

---

### Task 2: Implement `setProgress` in `ReadingSessionRepositoryImpl`

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/ReadingSessionRepositoryImpl.kt`

- [ ] **Step 1: Write the failing test**

Open `core/data/src/test/kotlin/com/riffle/core/data/ProgressSyncCycleTest.kt` and add these two tests inside the `ProgressSyncCycleTest` class (after the existing tests):

```kotlin
@Test
fun `setProgress bumps localUpdatedAt and attempts PATCH`() = runTest {
    val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
    val api = FakeSessionApi(
        getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
        patchResult = NetworkSyncSessionResult.Success(2_000L),
    )
    val repo = buildRepo(api, positionStore)

    repo.setProgress("item-1", 1.0f)

    assertEquals(1, api.patchCallCount)
    assertNotNull(positionStore.updatedTimestamp)
    assertTrue(positionStore.updatedTimestamp!! > 0L)
}

@Test
fun `setProgress bumps localUpdatedAt even when PATCH fails`() = runTest {
    val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
    val api = FakeSessionApi(
        getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
        patchResult = NetworkSyncSessionResult.NetworkError(IOException("network down")),
    )
    val repo = buildRepo(api, positionStore)

    repo.setProgress("item-1", 1.0f)

    assertNotNull(positionStore.updatedTimestamp)
    assertTrue(positionStore.updatedTimestamp!! > 0L)
}
```

Also add `FakePositionStore` support for `load()` — the existing fake returns `null` which is correct. No change needed there.

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.ProgressSyncCycleTest.setProgress*" --quiet
```

Expected: FAILED — `setProgress` not yet implemented.

- [ ] **Step 3: Implement `setProgress` in `ReadingSessionRepositoryImpl`**

Add the following method to `ReadingSessionRepositoryImpl` (after `runSyncCycle`):

```kotlin
override suspend fun setProgress(itemId: String, progress: Float) {
    val cfi = positionStore.load(itemId) ?: ""
    positionStore.updateLocalTimestamp(itemId, System.currentTimeMillis())
    val (baseUrl, token) = resolveCredentials() ?: return
    api.syncEbookProgress(
        baseUrl, itemId,
        NetworkEbookProgressPayload(cfi, progress),
        token, insecureAllowed(),
    )
    // PATCH failure intentionally ignored — next sync cycle will push
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.riffle.core.data.ProgressSyncCycleTest.setProgress*" --quiet
```

Expected: `BUILD SUCCESSFUL` with 2 tests passing.

- [ ] **Step 5: Run the full data test suite to catch regressions**

```bash
./gradlew :core:data:testDebugUnitTest --quiet
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSessionRepository.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ReadingSessionRepositoryImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ProgressSyncCycleTest.kt
git commit -m "feat(sync): add setProgress to ReadingSessionRepository for direct progress override"
```

---

### Task 3: Add `markAsRead` / `markAsUnread` to `LibraryItemDetailViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt`

- [ ] **Step 1: Inject `ReadingSessionRepository` and add the two methods**

In `LibraryItemDetailViewModel.kt`, add `ReadingSessionRepository` to the constructor and add the two methods. The full updated class constructor and new methods:

```kotlin
@HiltViewModel
class LibraryItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val sessionRepository: ReadingSessionRepository,  // ADD THIS
) : ViewModel() {
```

Add these two methods after `markOpened()`:

```kotlin
fun markAsRead() {
    viewModelScope.launch {
        repository.updateReadingProgress(itemId, 1.0f)
        sessionRepository.setProgress(itemId, 1.0f)
        val current = _uiState.value
        if (current is LibraryItemDetailUiState.Ready) {
            _uiState.value = current.copy(item = current.item.copy(readingProgress = 1.0f))
        }
    }
}

fun markAsUnread() {
    viewModelScope.launch {
        repository.updateReadingProgress(itemId, 0.0f)
        sessionRepository.setProgress(itemId, 0.0f)
        val current = _uiState.value
        if (current is LibraryItemDetailUiState.Ready) {
            _uiState.value = current.copy(item = current.item.copy(readingProgress = 0.0f))
        }
    }
}
```

Also add the import at the top of the file:

```kotlin
import com.riffle.core.domain.ReadingSessionRepository
```

- [ ] **Step 2: Verify the app module compiles**

```bash
./gradlew :app:compileDebugKotlin --quiet
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailViewModel.kt
git commit -m "feat(library): add markAsRead/markAsUnread to LibraryItemDetailViewModel"
```

---

### Task 4: Create `ReadToggleButton` composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/library/ReadToggleButton.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ReadToggleButton(
    isRead: Boolean,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = 40.dp
    if (isRead) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onMarkAsUnread),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Mark as unread",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onMarkAsRead),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Mark as read",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin --quiet
```

Expected: `BUILD SUCCESSFUL`

---

### Task 5: Wire `ReadToggleButton` into `LibraryItemDetailScreen`

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt`

- [ ] **Step 1: Add callbacks to `LibraryItemDetailContent` signature**

Change the function signature of `LibraryItemDetailContent` from:

```kotlin
private fun LibraryItemDetailContent(
    item: LibraryItem,
    token: String,
    downloadState: DownloadState,
    onReadItem: (LibraryItem) -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
)
```

to:

```kotlin
private fun LibraryItemDetailContent(
    item: LibraryItem,
    token: String,
    downloadState: DownloadState,
    onReadItem: (LibraryItem) -> Unit,
    onMarkAsRead: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 2: Add `ReadToggleButton` to the action row**

In `LibraryItemDetailContent`, find the action row (inside the `if (item.isSupported)` block) and add the toggle between the Read button and the DownloadButton:

Replace:

```kotlin
if (item.isSupported) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onReadItem(item) },
            enabled = downloadState !is DownloadState.InProgress,
            modifier = Modifier.weight(1f),
        ) {
            Text("Read")
        }
        DownloadButton(
            state = downloadState,
            onDownload = onDownload,
            onRemove = onRemove,
        )
    }
}
```

with:

```kotlin
if (item.isSupported) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onReadItem(item) },
            enabled = downloadState !is DownloadState.InProgress,
            modifier = Modifier.weight(1f),
        ) {
            Text("Read")
        }
        ReadToggleButton(
            isRead = item.readingProgress >= 0.99f,
            onMarkAsRead = onMarkAsRead,
            onMarkAsUnread = onMarkAsUnread,
        )
        DownloadButton(
            state = downloadState,
            onDownload = onDownload,
            onRemove = onRemove,
        )
    }
}
```

- [ ] **Step 3: Pass callbacks from the `Ready` branch in `LibraryItemDetailScreen`**

Find the `LibraryItemDetailContent(...)` call in the `Ready` branch and update it to pass the new callbacks:

Replace:

```kotlin
LibraryItemDetailContent(
    item = state.item,
    token = viewModel.authToken,
    downloadState = downloadState,
    onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
    onDownload = { viewModel.startDownload() },
    onRemove = {
        viewModel.removeDownload()
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Download removed",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.startDownload()
            }
        }
    },
    modifier = Modifier.padding(padding),
)
```

with:

```kotlin
LibraryItemDetailContent(
    item = state.item,
    token = viewModel.authToken,
    downloadState = downloadState,
    onReadItem = { item -> viewModel.markOpened(); onReadItem(item) },
    onMarkAsRead = { viewModel.markAsRead() },
    onMarkAsUnread = { viewModel.markAsUnread() },
    onDownload = { viewModel.startDownload() },
    onRemove = {
        viewModel.removeDownload()
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Download removed",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.startDownload()
            }
        }
    },
    modifier = Modifier.padding(padding),
)
```

- [ ] **Step 4: Verify the app compiles**

```bash
./gradlew :app:compileDebugKotlin --quiet
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the full unit test suite**

```bash
./gradlew testDebugUnitTest --quiet
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/library/ReadToggleButton.kt \
        app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemDetailScreen.kt
git commit -m "feat(library): add read/unread toggle button to Library Item Detail Screen"
```

---

### Task 6: Update README and close the issue

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Mark issue 42 complete in README**

Find the line in README.md's Features list that corresponds to issue #42 (read/unread toggle) and change `- [ ]` to `- [x]`.

- [ ] **Step 2: Commit and push**

```bash
git add README.md
git commit -m "chore: mark read/unread toggle (#42) as complete in README"
```
