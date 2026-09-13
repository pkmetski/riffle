# 12 — Reader Settings: Force Paginated in Landscape (iOS)

## 12.10 forcePaginatedInLandscape default is false

**Given** the default `FormattingPreferences` instance.
**Then** `forcePaginatedInLandscape` is `false` (opt-in, not on by default).

**Coverage:** `ReaderSettingsTests.testForcePaginatedInLandscapeDefaultIsFalse`

## 12.11 effectiveOrientation() logic is covered by JVM tests

The full override path (flag=true + landscape → Horizontal) is covered by JVM tests in
`FragmentConfigurationMapperTest`. The Kotlin/Native Swift API name for top-level extension
functions in `FormattingPreferences.kt` could not be confirmed without a framework build, so
a direct Swift call was not added.

**iOS rendering gap:** The iOS reader's Swift coordinator does not yet call `effectiveOrientation()`
before passing preferences to the Readium iOS navigator. Tracked as a follow-up.
