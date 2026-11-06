---
name: active-source-position-key-bug
description: "saveReadingPosition/loadPosition used activeSource.id as key instead of item.sourceId, causing progress loss for non-active-source books opened from the Riffle home screen"
metadata: 
  node_type: memory
  type: reference
  originSessionId: de38eb6d-b6ce-4a99-8d9b-aca20111506a
  modified: 2026-09-09T19:52:16.826Z
---

All three reader repositories (EpubRepositoryImpl, PdfRepositoryImpl, CbzRepositoryImpl) previously keyed reading position storage on `activeSource.id` rather than `item.sourceId`. This caused silent data loss: a book from a non-active source (e.g. Chitanka when ABS is active) would have its position saved/loaded under the wrong key, making the reader always start from the beginning.

**Fix (branch pkmetski/downloaded-book-not-found):**
- Added `sourceId: String` as first parameter to `saveReadingPosition` in `EpubRepository`, `CbzRepository`, and `PdfRepository` interfaces
- Changed `openEpub`/`openCbz`/`openPdf` to load position via `positionStore.load(item.sourceId, item.id)`
- Updated all ViewModels to pass `navServerId`/`navSourceId` to `saveReadingPosition`
- `ReadaloudSession.flushReadaloudPositionToStores` uses the `readerSyncServerId` already in scope

**Pattern to watch:** Any code using `sourceRepository.getActive()?.id` as a key for item-scoped data is wrong when the Riffle home screen can show items from non-active sources.
