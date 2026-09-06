# 16 — Comics / CBZ Reader UI (iOS)

## 16.1 CBZ nav row is a thumbnail strip, not a Material Slider

**Given** the CBZ reader is open with a multi-page comic.
**When** the navigation row at the bottom of the screen renders.
**Then** the row shows small thumbnail images for each page, not a slider track.

**Coverage:** `CbzThumbnailStripTest.renders_thumbnails_and_tap_routes_to_onSeek`

**iOS gap:** UI-only rendering test; verified manually via Xcode build on the CBZ reader.

## 16.2 Tapping a thumbnail seeks to that page

**Given** the thumbnail strip is visible.
**When** the user taps the thumbnail for page N.
**Then** the seek callback is invoked with the index of the tapped page.

**Coverage:** `CbzThumbnailStripTest.renders_thumbnails_and_tap_routes_to_onSeek`

## 16.3 Pre-populated thumbnail cache avoids redundant decodes

**Given** the thumbnail cache already contains decoded bitmaps for all pages.
**When** the thumbnail strip renders.
**Then** no additional image decode / stream-open calls are made (cache hits only).

**Coverage:** `CbzThumbnailStripTest` (cache-hit test)

**iOS gap:** Thumbnail caching is an internal implementation detail. The equivalent on iOS (NSCache / image cache) is verified via unit test on the iOS image source binding, not via a UI test.
