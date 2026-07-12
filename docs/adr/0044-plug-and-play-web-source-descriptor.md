# 0044 — Plug-and-play sources via `WebSourceDescriptor`

## Status

Accepted — Phases 1–5 landed 2026-07-12. Phase 6 (third-source validation
spike) is left as a follow-up: the fresh Gutenberg install-to-browse flow was
exercised on-device as part of this PR's smoke test and validated every seam,
so a real third source can slot in without further descriptor changes.

## Context

Adding **Project Gutenberg** (PR #516) touched 55 files across `app/`,
`core/data/`, `core/domain/`, `core/database/`, `core/logging/`, and `docs/` —
even though the source's *actual* code (Gutendex parser, HTTP client, catalog)
lives in a self-contained new module `core:catalog-gutenberg`. The additional
edits were spread across:

- `SourceType` enum + every exhaustive `when` downstream.
- `MainScreen.kt` — hard-coded route constants (`ADD_CHITANKA`,
  `GUTENBERG_BROWSE`, …), `when(sourceType)` in `libraryEntryRoute`, per-source
  `composable(…)` blocks.
- `SourceTypePickerScreen`/`ViewModel` — sealed `SourceTypeChoice` variant per
  source; one `hasXSource: StateFlow<Boolean>` per source in the VM.
- `SourceIconResolver` — exhaustive `when(source.type)` (compile-error-on-miss
  is intentional here).
- `NavigationDrawerComposable` — three `when(source.type)` branches for
  display-name / subtitle / icon and multiple negative-list checks
  (`type != LOCAL_FILES && type != CHITANKA && type != GUTENBERG`).
- `SourcesSection.kt` + `SettingsViewModel.kt` — per-source parameters
  (`chitankaSource`, `onRemoveChitankaSource`, …) and near-identical
  `ChitankaSourceRow` / `GutenbergSourceRow` composables.
- `SourceInstaller` — one class per singleton source, differing only in
  displayed name / placeholder URL / default library.
- Browse VM / screen / add VM / screen — ~280-line VMs per source that differ
  mainly by page size and provider blurb.

Every one of those was **the same shape of edit** for every source. Adding a
third source (Standard Ebooks, OPDS, …) would repeat the entire cascade. The
architecture forces the app to know a new source's identity in ~8 seams; it
should know it in ~1.

## Decision

Replace the eight seams with **one abstraction**: a `WebSourceDescriptor`
interface that a plugin module registers via a Hilt `@IntoSet` multibinding.
The `SourceType` enum stays (Room `sourceType` columns store its `.name`;
migrations depend on it) but its role shrinks to *"a stable string id for
storage"*. All UI and behaviour flow through the descriptor set.

### Descriptor shape

```kotlin
// core/domain
interface WebSourceDescriptor {
    val type: SourceType
    val displayName: String
    val subtitle: String? get() = null

    // Capabilities
    val isSingleton: Boolean get() = true
    val hasCredentials: Boolean get() = false
    val hasNetworkHost: Boolean get() = false
}
```

Each concrete descriptor is a top-level Kotlin `object`
(`ChitankaWebSourceDescriptor`, `GutenbergWebSourceDescriptor`, …). Two
consumption paths:

- **Composables and pure helpers** — `WebSourceDescriptors.forTypeOrError(type)`
  reads the static registry directly. No Hilt threading through the composable
  tree.
- **Hilt-managed classes** (installer, VMs) — inject `WebSourceRegistry`, which
  wraps the same set through `@Provides` / `@IntoSet` bindings.

### Compile-safety trade-off

Today, a new `SourceType` entry breaks every exhaustive `when` until it's
handled. After: a missing descriptor is a **runtime** failure. Mitigation: a
`WebSourceRegistryCompletenessTest` iterates `SourceType.values()` and asserts
every entry has a registered descriptor. `SourceIconResolver` **retains** its
exhaustive `when` because `@DrawableRes Int` values are Android-only and can't
be referenced from `:core:domain` — one seam per new source (drawable + one
line in the resolver) is an acceptable cost, and the compile-time guarantee is
worth preserving for icons specifically.

### Non-goals

- Not changing `SourceType.name` storage semantics — Room columns and
  migrations depend on it.
- Not folding ABS / LOCAL_FILES bespoke rows into the generic surface. ABS is
  multi-server with per-server credentials; LOCAL_FILES has a folder picker.
  Trying to unify them would re-expand the descriptor with special-case flags,
  defeating the point. Keep them bespoke; the descriptor set covers the
  **web-catalog singleton** family that Chitanka / Gutenberg / next-source
  share.
- Not collapsing `CatalogFactory` into the descriptor — they are complementary
  Hilt bindings and mixing them creates a circular-init hazard.

## Rollout

Six independent PRs (this ADR is being landed with Phase 1):

1. **Descriptor + registry scaffolding** — introduce `WebSourceDescriptor`,
   static `WebSourceDescriptors`, Hilt-bound `WebSourceRegistry`,
   `@IntoSet` multibinding. Register ABS / LocalFiles / Chitanka / Gutenberg.
   Add completeness test. **Partial Phase-2 down-payment:**
   `NavigationDrawerComposable`'s `sourceDisplayName` / `sourceSubtitle`
   consume the descriptor.
2. **Icons + drawer + settings** — remaining drawer `when`s, `SourcesSection`
   per-source rows collapse to generic `SingletonSourceRow(descriptor)`.
   `SettingsViewModel` per-source props (`chitankaSource`, `gutenbergSource`)
   collapse to `singletonSources: List<Source>`. `SourceTypePickerViewModel`
   collapses three `hasXSource` flows into one `installedTypes: Set<SourceType>`.
3. **`NavContribution`** — extend descriptor with an optional nav
   contribution (`addRoute`, `browseRoute`, `libraryEntryRoute(...)`,
   `registerAdd/Browse(builder, nav)`). `MainScreen.kt` iterates
   `registry.all.mapNotNull { it.navContribution }` and registers routes.
4. **`GenericSingletonSourceInstaller(descriptor)`** — delete
   `ChitankaSourceInstaller` / `GutenbergSourceInstaller`. Descriptor supplies
   id / placeholder URL / default library name.
5. **Browse VM extraction (Option B)** — introduce `UnboundedBrowseViewModel`
   base; per-source VMs shrink to ~5 lines each. Add screen generalisation.
   (Option A — a single generic VM with descriptor-supplied slots — is a
   possible future consolidation but not required now.)
6. **Third-source validation spike** — implement Standard Ebooks or OPDS
   end-to-end. If any file **outside** the plugin module has to be touched,
   that's a regression on this ADR and we fix upstream.

Independent PRs are important: each phase touches ~5–10 files with test
updates, and bundling them into one PR was rejected on review-scale grounds
after the first attempt. Each phase's tests must be green before the next
begins.

## Consequences

**Positive.** Adding a new web source (e.g. Standard Ebooks) will touch ~8
files: the catalog Gradle module, one `SourceType` enum entry, one Hilt module
(descriptor + `CatalogFactory`), one drawable + one line in
`SourceIconResolver` + one line in its test, and a descriptor test.
`MainScreen.kt`, `NavigationDrawerComposable.kt`, `SourcesSection.kt`,
`SettingsViewModel.kt`, and `SourceTypePickerScreen.kt` are never touched again.

**Negative.** Compile-time exhaustiveness on `when(sourceType)` shrinks from
"every seam" to "icons only". Mitigated by
`WebSourceRegistryCompletenessTest`.

**Risk — descriptor bloat.** As sources diverge, we'll be tempted to add
`hasXFeature: Boolean` flags. Rule: keep the descriptor to display + capability
data. Behavioural specialisation belongs in the plugin's own module.
