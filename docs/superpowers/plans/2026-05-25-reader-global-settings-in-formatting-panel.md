# Reader Global Settings in Formatting Panel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface "Keep screen on" and "Volume key navigation" (global settings) as an editable section inside the EPUB reader's `FormattingPanel` bottom sheet, alongside the existing per-book formatting settings.

**Architecture:** Add `VolumeKeyPreferencesStore` injection to `EpubReaderViewModel` alongside the existing `WakeLockPreferencesStore`, expose three new StateFlows and setter methods, pass them through `EpubReaderScreen` into `FormattingPanel`, and render a labeled divider section with toggle rows at the bottom of the sheet. Changes write directly to the global DataStore stores — the global Settings screen stays in sync automatically.

**Tech Stack:** Kotlin, Hilt, Jetpack Compose, Material 3, Kotlin Coroutines / StateFlow, Android DataStore.

---

## File Map

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` | Inject `VolumeKeyPreferencesStore`; expose `volumeKeyNavigationEnabled` + `invertVolumeKeys` StateFlows; add `setKeepScreenOn`, `setVolumeKeyNavigationEnabled`, `setInvertVolumeKeys` methods |
| `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt` | Add 6 new parameters; move Reset button before the global section; add labeled divider + 3 toggle rows |
| `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` | Collect 3 new StateFlows; pass values + callbacks into `FormattingPanel` |

---

## Task 1: Expose global pref StateFlows and setters in EpubReaderViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Add `VolumeKeyPreferencesStore` to the constructor**

  In `EpubReaderViewModel`, add the new dependency between `wakeLockPreferencesStore` and `volumeNavigationController`:

  ```kotlin
  @HiltViewModel
  class EpubReaderViewModel @Inject constructor(
      application: Application,
      savedStateHandle: SavedStateHandle,
      private val libraryRepository: LibraryRepository,
      private val epubRepository: EpubRepository,
      private val assetRetriever: AssetRetriever,
      private val publicationOpener: PublicationOpener,
      private val readingSessionRepository: ReadingSessionRepository,
      private val formattingPreferencesStore: FormattingPreferencesStore,
      private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
      private val wakeLockPreferencesStore: WakeLockPreferencesStore,
      private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,  // ← add this
      private val volumeNavigationController: VolumeNavigationController,
      private val readerStateHolder: ReaderStateHolder,
  ) : AndroidViewModel(application) {
  ```

  Also add the import at the top of the file:
  ```kotlin
  import com.riffle.core.domain.VolumeKeyPreferencesStore
  ```

- [ ] **Step 2: Expose `volumeKeyNavigationEnabled` and `invertVolumeKeys` StateFlows**

  After the existing `keepScreenOn` StateFlow (line 112), add:

  ```kotlin
  val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
      .stateIn(viewModelScope, SharingStarted.Eagerly, true)

  val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)
  ```

- [ ] **Step 3: Add setter methods for all three global prefs**

  After `resetToGlobalDefaults()` at the end of the file (before the closing `}`), add:

  ```kotlin
  fun setKeepScreenOn(value: Boolean) {
      viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
  }

  fun setVolumeKeyNavigationEnabled(value: Boolean) {
      viewModelScope.launch { volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(value) }
  }

  fun setInvertVolumeKeys(value: Boolean) {
      viewModelScope.launch { volumeKeyPreferencesStore.setInvertVolumeKeys(value) }
  }
  ```

- [ ] **Step 4: Build to verify Hilt can satisfy the new dependency**

  ```bash
  ./gradlew :app:compileDebugKotlin
  ```

  Expected: BUILD SUCCESSFUL. Hilt already binds `VolumeKeyPreferencesStore` → `VolumeKeyPreferencesStoreImpl` in `DataModule`; no new binding needed.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
  git commit -m "feat(reader): expose volume key prefs and global setting setters in EpubReaderViewModel"
  ```

---

## Task 2: Add "Also while reading" global settings section to FormattingPanel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

- [ ] **Step 1: Add the six new parameters to the `FormattingPanel` composable signature**

  Replace the existing signature:
  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun FormattingPanel(
      prefs: FormattingPreferences,
      hasBookOverrides: Boolean,
      onPrefsChange: (FormattingPreferences) -> Unit,
      onReset: () -> Unit,
      onDismiss: () -> Unit,
  ) {
  ```

  With:
  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun FormattingPanel(
      prefs: FormattingPreferences,
      hasBookOverrides: Boolean,
      onPrefsChange: (FormattingPreferences) -> Unit,
      onReset: () -> Unit,
      onDismiss: () -> Unit,
      keepScreenOn: Boolean,
      onKeepScreenOnChange: (Boolean) -> Unit,
      volumeKeyNavigationEnabled: Boolean,
      onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
      invertVolumeKeys: Boolean,
      onInvertVolumeKeysChange: (Boolean) -> Unit,
  ) {
  ```

- [ ] **Step 2: Move the Reset button and add the global settings section**

  Replace the existing bottom of the Column (from `Spacer(Modifier.height(8.dp))` through to `Spacer(Modifier.height(24.dp))`):

  ```kotlin
  // existing code to keep above:
  // ...Chapter Map Row...

  Spacer(Modifier.height(8.dp))
  TextButton(
      onClick = onReset,
      enabled = hasBookOverrides,
      modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .alpha(if (hasBookOverrides) 1f else 0f),
  ) {
      Text("Reset to global defaults")
  }
  ```

  With this block (replacing from `Spacer(Modifier.height(8.dp))` after Chapter Map through `Spacer(Modifier.height(24.dp))`):

  ```kotlin
  Spacer(Modifier.height(8.dp))
  TextButton(
      onClick = onReset,
      enabled = hasBookOverrides,
      modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .alpha(if (hasBookOverrides) 1f else 0f),
  ) {
      Text("Reset to global defaults")
  }

  Spacer(Modifier.height(16.dp))

  // "Also while reading" section — global settings surfaced here for convenience;
  // changes write to the same global DataStore as the Settings screen.
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
  ) {
      HorizontalDivider(modifier = Modifier.weight(1f))
      Text(
          "Also while reading",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp),
      )
      HorizontalDivider(modifier = Modifier.weight(1f))
  }

  Spacer(Modifier.height(8.dp))

  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
          .fillMaxWidth()
          .clickable { onKeepScreenOnChange(!keepScreenOn) },
  ) {
      Column(modifier = Modifier.weight(1f)) {
          Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
          Text(
              "Applies to all books",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      }
      Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOnChange)
  }

  Spacer(Modifier.height(4.dp))

  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
          .fillMaxWidth()
          .clickable { onVolumeKeyNavigationEnabledChange(!volumeKeyNavigationEnabled) },
  ) {
      Column(modifier = Modifier.weight(1f)) {
          Text("Volume key navigation", style = MaterialTheme.typography.bodyLarge)
          Text(
              "Applies to all books",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      }
      Switch(
          checked = volumeKeyNavigationEnabled,
          onCheckedChange = onVolumeKeyNavigationEnabledChange,
      )
  }

  Spacer(Modifier.height(4.dp))

  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = volumeKeyNavigationEnabled) { onInvertVolumeKeysChange(!invertVolumeKeys) }
          .alpha(if (volumeKeyNavigationEnabled) 1f else 0.38f),
  ) {
      Column(modifier = Modifier.weight(1f)) {
          Text("Invert volume keys", style = MaterialTheme.typography.bodyLarge)
          Text(
              "Applies to all books",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      }
      Switch(
          checked = invertVolumeKeys,
          onCheckedChange = onInvertVolumeKeysChange,
          enabled = volumeKeyNavigationEnabled,
      )
  }

  Spacer(Modifier.height(24.dp))
  ```

- [ ] **Step 3: Add missing imports**

  At the top of `FormattingPanel.kt`, add these imports (alongside the existing ones):
  ```kotlin
  import androidx.compose.foundation.clickable
  import androidx.compose.material3.HorizontalDivider
  ```

- [ ] **Step 4: Build to verify no compile errors**

  ```bash
  ./gradlew :app:compileDebugKotlin
  ```

  Expected: BUILD SUCCESSFUL. The call site in `EpubReaderScreen` will be a compile error until Task 3 — that's fine; both files are being changed.

  > If you need it to compile before Task 3, temporarily add default values to the new parameters (e.g., `keepScreenOn: Boolean = true`) and remove them after Task 3.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
  git commit -m "feat(reader): add global settings section to FormattingPanel"
  ```

---

## Task 3: Wire up EpubReaderScreen to pass global pref state into FormattingPanel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Collect the new StateFlows**

  In `EpubReaderScreen`, after the existing `val keepScreenOn by viewModel.keepScreenOn.collectAsState()` (line 73), add:

  ```kotlin
  val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
  val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
  ```

- [ ] **Step 2: Pass values and callbacks into FormattingPanel**

  Replace the existing `FormattingPanel(...)` call site (lines 226–234):

  ```kotlin
  if (showFormattingPanel) {
      FormattingPanel(
          prefs = formattingPrefs,
          hasBookOverrides = hasBookOverrides,
          onPrefsChange = { viewModel.updateFormatting(it) },
          onReset = { viewModel.resetToGlobalDefaults() },
          onDismiss = { showFormattingPanel = false },
          keepScreenOn = keepScreenOn,
          onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
          volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
          onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
          invertVolumeKeys = invertVolumeKeys,
          onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
      )
  }
  ```

- [ ] **Step 3: Build and verify**

  ```bash
  ./gradlew :app:compileDebugKotlin
  ```

  Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4: Run unit tests to confirm nothing regressed**

  ```bash
  ./gradlew :app:testDebugUnitTest
  ```

  Expected: All tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
  git commit -m "feat(reader): wire global pref state and setters into FormattingPanel"
  ```

---

## Manual Verification Checklist

After all tasks are complete, verify the following manually in the emulator or on a device:

- [ ] Open an EPUB book → tap the Format (Settings) icon → the bottom sheet shows a labeled "Also while reading" section below the per-book settings
- [ ] Toggle "Keep screen on" in the sheet → screen stays awake / sleeps accordingly
- [ ] Toggle "Volume key navigation" in the sheet → volume keys turn pages / don't
- [ ] "Invert volume keys" row is dimmed and unresponsive when "Volume key navigation" is off
- [ ] Toggle any global setting in the sheet → open the main Settings screen → the toggle reflects the new value
- [ ] Toggle a global setting in the main Settings screen → open the reader → the sheet reflects the updated value
- [ ] "Reset to global defaults" button resets per-book formatting but does NOT affect the global toggles
