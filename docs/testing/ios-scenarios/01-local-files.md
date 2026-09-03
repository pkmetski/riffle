# iOS Scenario 01 — Local Files folder picker

## Feature
User can add a local folder of EPUB/PDF/CBZ books via the iOS document picker; books appear in the library.

## Scenarios

### Scenario A: Pick a folder with books
1. Launch the app with no sources configured. HomeScreen shows "Add a source to get started" and "Add Local Files" button.
2. Tap "Add Local Files". iOS document picker opens showing the Files app.
3. Navigate to a folder containing at least one `.epub` file. Tap "Open".
4. The picker dismisses. HomeScreen shows "Scanning folder…" spinner.
5. Spinner disappears; success message shows "Added N books".
6. HomeScreen transitions to the library view showing the added books.

### Scenario B: Cancel the picker
1. Launch the app with no sources configured.
2. Tap "Add Local Files". Picker opens.
3. Tap Cancel (top-left).
4. Picker dismisses. HomeScreen returns to the "Add Local Files" button state with no error message.

### Scenario C: Folder with no supported files
1. Tap "Add Local Files". Pick a folder that contains only `.txt` files.
2. HomeScreen shows "Added 0 books" (no crash, no error message).

## Test locations
iOS XCTest: `iosApp/iosAppTests/LocalFilesTests.swift`
