# 5 — Navigation Drawer (iOS)

## 5.1 Storyteller sources excluded from drawer

**Given** the app has an ABS source and a Storyteller peer source configured.
**When** the navigation drawer is opened.
**Then** only the ABS source appears in the server switcher; the Storyteller peer is not listed.

**Coverage:** `DrawerViewModelTest.allServersFiltersOutStorytellerService`

## 5.2 Readaloud library excluded from library list

**Given** the active server has a regular book library and a Readaloud pseudo-library.
**When** the navigation drawer is opened.
**Then** only the regular book library is listed; the Readaloud library is not shown.

**Coverage:** `DrawerViewModelTest.visibleLibrariesFiltersReadaloudLibraries`

## 5.3 Hidden library excluded from library list

**Given** the user has hidden a library via the visibility settings.
**When** the navigation drawer is opened.
**Then** the hidden library is not listed.

**Coverage:** `DrawerViewModelTest.visibleLibrariesFiltersHiddenIds`

## 5.4 Burger tap opens the drawer

**Given** the library screen is visible.
**When** the user taps the hamburger icon (☰) in the top bar.
**Then** the drawer panel slides in from the left and the scrim appears.

**Coverage:** `NavDrawerTests.testBurgerTapOpensDrawer`

## 5.5 Scrim tap closes the drawer

**Given** the drawer is open.
**When** the user taps the scrim (area outside the panel).
**Then** the drawer closes.

**Coverage:** `NavDrawerTests.testScrimTapClosesDrawer`
