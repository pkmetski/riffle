# ADR 0002 — Android-first with KMP-ready layer separation

**Status:** Accepted

## Context

iOS is a potential future target but not in scope for the initial build. The question is whether to invest in cross-platform infrastructure now or defer it entirely.

## Decision

Build Android-only initially, but enforce strict separation between the domain/data layer and the Android UI layer:

- **Domain and data layer** — pure Kotlin, no Android framework imports. ABS API client, sync engine, download manager, progress tracking all live here. These are the natural KMP shared module candidates.
- **UI layer** — Android/Compose specific. Embeds Readium Kotlin navigator as a Fragment.

Platform-specific dependencies in the data layer (Room, OkHttp) should be hidden behind interfaces so they can be swapped for KMP-compatible equivalents (SQLDelight, Ktor) when iOS is targeted.

## Consequences

- No KMP Gradle setup or shared module overhead at project start.
- The iOS migration path is: move domain + data to a shared KMP module, swap platform implementations, build SwiftUI + Readium Swift UI on top.
- Discipline required: Android-specific APIs (Context, Uri, etc.) must not leak into the domain layer.
