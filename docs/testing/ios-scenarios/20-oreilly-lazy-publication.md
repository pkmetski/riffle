# Scenario 20 — O'Reilly lazy per-chapter publication

## Context

O'Reilly books are not regular EPUB downloads. Opening an O'Reilly book on iOS uses the
`LazyPublicationCapability` detected from `OReillyCatalog`, builds an in-memory Readium `Publication`
backed by `OReillyLazyContainer`, and streams each chapter HTML on first render.

Android reference: `OReillyLazyContainer`, `OReillyPublicationBuilder`, `EpubReaderViewModel.openLazyPublication`.

## Scenarios

### 20-A: Shape JSON decoding

- **Given** a valid shape JSON (produced by `IosLazyChapterFetcherImpl.serializeShape`)
- **When** `OReillyPublicationBuilder.build(shapeJson:fetcher:)` is called
- **Then** the returned `Publication.metadata.title` matches the JSON `title` field
- **And** `publication.readingOrder.count` equals the number of spine items

### 20-B: URL stripping (`extractRelativePath`)

- `https://readium_package/assets/cover.png` → `assets/cover.png`
- `/xhtml/ch01.xhtml` → `xhtml/ch01.xhtml`
- `images/fig.png` → `images/fig.png` (unchanged)

### 20-C: Chapter fetch delegation

- **Given** a `StubLazyChapterFetcher` with a canned XHTML file path for `"xhtml/ch01.xhtml"`
- **When** `container["xhtml/ch01.xhtml"].read(range: nil)` is awaited
- **Then** the returned `Data` matches the XHTML file contents
- **And** `fetchChapterCallCount == 1`

- **Given** a `StubLazyChapterFetcher` with no paths registered
- **When** `container["xhtml/ch01.xhtml"].read(range: nil)` is awaited
- **Then** result is `.failure(.decoding(nil))`

### 20-D: Bridge wiring

- **When** `ReadiumEpubNavigatorBridge.openLazyEpub(shapeJson:locatorJson:fetcher:)` is called with valid JSON
- **Then** no crash occurs (Readium navigator opens asynchronously on the main queue)

### 20-E: Invalid JSON

- **When** `OReillyPublicationBuilder.build(shapeJson: "not json", ...)` is called
- **Then** a `BuildError.invalidShapeJson` is thrown

## XCTest coverage

`iosAppTests/OReillyLazyPublicationTests.swift` covers scenarios 20-A through 20-E using
`StubLazyChapterFetcher`.
