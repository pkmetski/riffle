# PDF Immersive Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add immersive mode to the PDF reader so it behaves identically to the EPUB reader — system bars hidden on open, TopAppBar toggle on center tap, auto-dismiss overlay on page turns.

**Architecture:** `ImmersiveModeState` and `rememberImmersiveModeState()` are used unchanged. All changes are in `PdfReaderScreen.kt`: the `Scaffold` is replaced with a `Box` + floating `AnimatedVisibility` TopAppBar (same pattern as `EpubReaderScreen`), an `InputListener` tap listener is registered on `PdfiumNavigatorFragment` for center-tap immersive toggle, and `dismissOverlay()` is wired to the page-change callback.

**Tech Stack:** Kotlin, Jetpack Compose, Readium (`PdfiumNavigatorFragment`, `InputListener`, `TapEvent`), `WindowInsetsControllerCompat`

---

## Files

- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`
- Modify (test): `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt`
- No other files change.

---

## Task 1: Fix existing PDF harness test and add failing immersive test

The existing `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError` test will break once immersive mode is added (the TopAppBar title and Back button will be hidden). Fix it now so it still passes with the current code, and update it to work correctly after immersive is added. Then add a new test that asserts immersive behaviour — this test must **fail** with the current code (before implementation).

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt`

- [ ] **Step 1: Open the existing test**

Read `app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt`.

- [ ] **Step 2: Fix `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError`**

Two changes are needed:

1. The `waitUntil` block currently requires the item title text to be visible — remove that condition since the TopAppBar will be hidden after immersive mode is added.
2. Before asserting the Back button exists, add a center tap to reveal the overlay; in immersive mode the button is hidden until a tap.

Replace the body of `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError` with:

```kotlin
@Test
fun opensPdfNavigatesTwoPagesAndShowsPage3WithNoError() {
    addServerAndBrowseLibrary()

    composeTestRule.waitUntil(timeoutMillis = 15_000) {
        composeTestRule.onAllNodesWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).performClick()
    composeTestRule.tapReadInDetailScreen()

    composeTestRule.waitUntil(timeoutMillis = 20_000) {
        composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.assertNoErrorState()
    composeTestRule.waitUntilPdfLoaded()

    repeat(2) {
        composeTestRule
            .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
            .performTouchInput { click(centerRight) }
        composeTestRule.waitForIdle()
    }

    composeTestRule.waitUntilOnPdfPage(3)
    composeTestRule.assertNoErrorState()

    // Reveal overlay before asserting the Back button (overlay is hidden in immersive mode).
    composeTestRule
        .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
        .performTouchInput { click(center) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithContentDescription("Back").assertExists()
}
```

- [ ] **Step 3: Add the new failing immersive test**

Append this test to `PdfHarnessTest`. With the current (non-immersive) code the TopAppBar is always visible, so `assertDoesNotExist()` on "Back" fails — confirming the test is a valid red.

```kotlin
@Test
fun pdfReaderHidesTopAppBarOnOpenAndShowsItOnCenterTap() {
    addServerAndBrowseLibrary()

    composeTestRule.waitUntil(timeoutMillis = 15_000) {
        composeTestRule.onAllNodesWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithText(StubAbsServer.TEST_PDF_ITEM_TITLE).performClick()
    composeTestRule.tapReadInDetailScreen()

    composeTestRule.waitUntil(timeoutMillis = 20_000) {
        composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_READER_READY).fetchSemanticsNodes().isNotEmpty() ||
            composeTestRule.onAllNodesWithTag(ReaderSemanticMatchers.TAG_ERROR_STATE).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.assertNoErrorState()
    composeTestRule.waitUntilPdfLoaded()

    // TopAppBar must be hidden in immersive mode on open.
    composeTestRule.onNodeWithContentDescription("Back").assertDoesNotExist()

    // Center tap reveals overlay.
    composeTestRule
        .onNodeWithTag(ReaderSemanticMatchers.TAG_READER_READY)
        .performTouchInput { click(center) }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithContentDescription("Back").assertExists()
}
```

- [ ] **Step 4: Run only the new test to confirm it fails**

```
make harness-test
```

Expected: `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError` PASSES (with the fix it works against current non-immersive code), `pdfReaderHidesTopAppBarOnOpenAndShowsItOnCenterTap` FAILS with `assertDoesNotExist failed: expected the node to not exist`.

- [ ] **Step 5: Commit the test changes**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/harness/PdfHarnessTest.kt
git commit -m "test(pdf-reader): add failing immersive mode test and fix existing navigation test"
```

---

## Task 2: Implement immersive mode in PdfReaderScreen

Replace `Scaffold` with a floating-overlay layout and wire `ImmersiveModeState` for tap toggle and page-change auto-dismiss.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt`

- [ ] **Step 1: Replace the imports block**

Replace the entire imports section of `PdfReaderScreen.kt` with:

```kotlin
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator
```

- [ ] **Step 2: Replace the `PdfReaderScreen` composable**

Replace the entire `PdfReaderScreen` function (lines 49–146) with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onReaderResumed()
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Screen wake lock — gated on user preference
    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

    val title = (state as? ReaderState.Ready)?.title ?: ""

    // TopAppBar floats as an overlay so its show/hide never resizes the PDF view —
    // same pattern as EpubReaderScreen.
    Box(modifier = Modifier.fillMaxSize()) {
        // navigationBarsPadding only — status bar insets are omitted because in immersive
        // mode the status bar is hidden, and the floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets when the user taps to reveal it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            when (val s = state) {
                ReaderState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                }
                is ReaderState.Ready -> {
                    val currentPage by viewModel.currentPage.collectAsState()
                    PdfNavigatorView(
                        state = s,
                        onPageChanged = { locator ->
                            immersiveState.dismissOverlay()
                            viewModel.onPageChanged(locator)
                        },
                        onTap = immersiveState::toggle,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_ready")
                            .semantics {
                                contentDescription = buildString {
                                    append(currentPage?.let { "page:$it" } ?: "")
                                    append(" wake-lock:")
                                    append(if (keepScreenOn) "on" else "off")
                                }
                            },
                    )
                }
                is ReaderState.Error -> {
                    Text(
                        s.message,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_error_state"),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !immersiveState.isImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + expandVertically(expandFrom = Alignment.Top),
            exit = slideOutVertically(targetOffsetY = { -it }) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    }
}
```

- [ ] **Step 3: Replace the `PdfNavigatorView` composable**

Replace the entire `PdfNavigatorView` function (lines 148–210) with:

```kotlin
@Composable
private fun PdfNavigatorView(
    state: ReaderState.Ready,
    onPageChanged: (Locator) -> Unit,
    onTap: () -> Unit,
    serverLocatorEvents: Flow<Locator>,
    volumeNavEvents: Flow<VolumeNavEvent>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val fragmentRef = remember { mutableStateOf<PdfiumNavigatorFragment?>(null) }

    // rememberUpdatedState ensures the listener always calls the latest onTap lambda
    // without needing to be re-created when onTap changes.
    val currentOnTap by rememberUpdatedState(onTap)
    val tapListener = remember {
        object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                currentOnTap()
                return false
            }
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            when (event) {
                VolumeNavEvent.Forward -> fragmentRef.value?.goForward(animated = false)
                VolumeNavEvent.Backward -> fragmentRef.value?.goBackward(animated = false)
            }
        }
    }

    DisposableEffect(tapListener) {
        onDispose { fragmentRef.value?.removeInputListener(tapListener) }
    }

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply { id = View.generateViewId() }
        },
        update = { containerView ->
            val fm = fragmentActivity.supportFragmentManager
            if (fm.findFragmentById(containerView.id) == null) {
                val fragmentFactory = PdfiumNavigatorFactory(
                    publication = state.publication,
                    pdfEngineProvider = PdfiumEngineProvider(),
                ).createFragmentFactory(
                    initialLocator = state.initialLocator,
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerView.id, PdfiumNavigatorFragment::class.java, null)
                    .commitNow()
                val fragment = fm.findFragmentById(containerView.id) as? PdfiumNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                // DirectionalNavigationAdapter handles edge taps for page navigation;
                // tapListener (registered after) receives only center taps that DA doesn't consume.
                fragment.addInputListener(
                    DirectionalNavigationAdapter(
                        navigator = fragment,
                        handleTapsWhileScrolling = true,
                    )
                )
                fragment.addInputListener(tapListener)
                fragmentActivity.lifecycleScope.launch {
                    fragment.currentLocator.collect { locator -> onPageChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
```

- [ ] **Step 4: Build to confirm no compile errors**

```
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the harness tests**

```
make harness-test
```

Expected: both `opensPdfNavigatesTwoPagesAndShowsPage3WithNoError` and `pdfReaderHidesTopAppBarOnOpenAndShowsItOnCenterTap` PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt
git commit -m "feat(pdf-reader): add immersive mode with floating TopAppBar overlay"
```
