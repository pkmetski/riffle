---
name: koin-generic-map-bindings-need-qualifier
description: Koin keys `single<Map<K,V>>` by the erased Map class — every Map binding in a module needs a `named(...)` qualifier or the last one silently replaces the others
metadata:
  type: reference
---

In Riffle's Koin graphs (`shared/src/iosMain/.../Koin.kt`, `CoreDataKoinModules.kt`) there are several `single<Map<SourceType, X>>` bindings (SourceAdapter, CatalogFactory, RemoteUserIdResolver). Koin resolves generics by erased class, so an **unqualified** `single<Map<...>>` overrides whichever Map binding came before it with no error (override is allowed by default) — the symptom is a `ClassCastException` or wrong map far from the binding site.

**How to apply:** every `single<Map<...>>` gets a `named(...)` qualifier, and its `get()` consumers pass the same qualifier. Shared qualifier constants live in `core/data/src/commonMain/kotlin/com/riffle/core/data/di/CoreDataQualifiers.kt` (e.g. `REMOTE_USER_ID_RESOLVERS_BY_SOURCE_TYPE`, moved there from androidMain in #1101 so iOS reuses it).

Related: `checkNoServerReferences` (riffleChecks) flags any **new** file that mentions the grandfathered `ServerType` enum; add the file to `ServerReferenceLint.ALLOWLIST` in buildSrc with a one-line justification rather than renaming the enum.
