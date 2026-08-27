# ADR 0063 — Panel Detection Engine Extraction and Live Definitions

**Date:** 2026-08-21
**Status:** Accepted

The panel detection algorithm (ADR 0055) is extracted to a private KMP library and redesigned as a **data-driven interpreter + definitions** pair, allowing detection improvements to ship to users without an app release.

## Decision

### Private library — `riffle-panel-engine`

The interpreter lives in a separate private GitHub repository published as a KMP multiplatform library via GitHub Packages. The app declares it as a Gradle dependency (resolved with a `gpr.token` — no token ships in the APK).

Source set split:
- `commonMain` — `PanelDetector`, `PanelMaskBinarizer`, `PanelOrchestrator`, all domain models, `PanelDetectionConfig`
- `androidMain` — `AndroidPageImageDecoder` (BitmapFactory)
- `iosMain` — `IosPageImageDecoder` (CoreGraphics)

All existing PNG fixtures and test suites move to the private repo. Tests exercise the interpreter against the reference definitions file; green tests are the CI gate for publishing.

### Definitions — `riffle-panel-definitions` (public repo)

Detection behaviour is encoded in a `definitions.json` pipeline spec rather than hardcoded constants:

```json
{
  "minInterpreterVersion": "1.0.0",
  "binarization": { "localAdaptiveConstant": 10, ... },
  "strategies": [
    { "type": "projection", "gutterThresholdFraction": 0.15, ... },
    { "type": "floodFill", "minComponentAreaFraction": 0.02 }
  ],
  "postProcessing": [
    { "type": "splitAtInternalGutters", ... },
    { "type": "repairOneSidedRowJunctions" },
    { "type": "repairDiagonalTwoColumnRows" },
    { "type": "deduplicateOverlapping" }
  ],
  "sanityChecks": { "minPanelDimensionFraction": 0.14, ... }
}
```

The private repo's CI auto-publishes `definitions.json` to the public repo on every green test run. Red tests leave the public definitions unchanged.

### App-side integration

`PanelModule` gains a `DefinitionsFetcher` that runs on app foreground (conditional GET, ETag-cached). It validates `minInterpreterVersion` against the library constant and falls back to a bundled `assets/panel-definitions.json` if the fetch fails or requires a newer interpreter. The resolved `PanelDetectionConfig` is injected as a singleton into `PanelOrchestrator`; the detector and orchestrator are network-unaware.

## Why not keep the algorithm hardcoded in the app

Detection failures accumulate faster than app releases. The reporting system (ADR 0062) creates a feedback loop, but fixes reaching users still required a release cycle. The interpreter+definitions split decouples tuning cadence (continuous) from release cadence (periodic).

## Why not a panels database (per-book lookup)

A lookup table only covers books explicitly added to it. The goal is generalised detection across any comic, so improvements must take the form of algorithm or parameter changes, not stored coordinates.

## Why not dynamic code loading

Android and iOS both prohibit or restrict loading executable code at runtime. The declarative pipeline spec is the correct boundary: the interpreter is compiled code (stable, audited), the definitions are data (updatable, safe to fetch).
