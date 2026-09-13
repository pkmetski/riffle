# 12 — Reader Settings: Force Paginated in Landscape (iOS)

## 12.10 forcePaginatedInLandscape default is false

**Given** the default `FormattingPreferences` instance.
**Then** `forcePaginatedInLandscape` is `false` (opt-in, not on by default).

**Coverage:** `ReaderSettingsTests.testForcePaginatedInLandscapeDefaultIsFalse`

## 12.11 effectiveOrientation() is callable from Swift and honours stored orientation when flag is off

**Given** the default `FormattingPreferences` (orientation=Horizontal, flag=false).
**When** `effectiveOrientation(isLandscape: true)` is called.
**Then** it returns `.horizontal` — the flag-off path leaves orientation unchanged.

**Note:** The full override path (flag=true + landscape → Horizontal) is covered by JVM tests. The iOS rendering path that reads effectiveOrientation in the Swift reader coordinator is an iOS gap — the function is available via Kotlin/Native but the iOS reader does not yet call it to resolve effective orientation before passing preferences to the Readium iOS navigator.

**Coverage:** `ReaderSettingsTests.testEffectiveOrientation_flagOff_returnsStoredOrientation`
