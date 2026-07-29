# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, or
- **`CONTEXT-MAP.md`** at the repo root if it exists — it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context repo (most repos):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Multi-context repo (presence of `CONTEXT-MAP.md` at the root):

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Module placement

`CONTEXT.md` leads with a **Module Map** table. When creating a new file, pick the innermost module whose dependency constraints the file satisfies:
- Shared business logic with no platform imports → KMP core (`core:common`, `core:models`, `core:domain`, `core:net`, `core:sources`, `core:sync`, or a `core:catalog-*` plugin).
- JVM/Android streaming APIs that expose `InputStream` → the `core:network` host shim; shared HTTP clients and DTOs belong in `core:net`.
- Code that needs Hilt wiring, `Context`, `DataStore`, or `android.*` → Android-hosting (`core:data`, `core:logging`, or `app`).
- Room entities / DAO contracts → `core:database-api/commonMain`; the generated database, migrations, drivers, and platform factories → `core:database`.

The `checkNoAndroidImports` CI task enforces the boundary. See [ADR 0049](../adr/0049-platform-agnostic-core-boundary.md).

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/grill-with-docs`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
