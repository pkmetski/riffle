# Word Lookup — kaikki.org On-Device Build

> **Amends:** `docs/superpowers/specs/2026-08-14-word-lookup-design.md`
>
> **Context:** ADR 0060 was implemented with a "pre-built server-hosted packs" model. This spec replaces that model with on-device build from kaikki.org source data — no hosted infrastructure required.

---

## What changes (summary)

| Component | Before | After |
|-----------|--------|-------|
| Pack source | Server-hosted pre-built `.db` | kaikki.org JSONL downloaded on device |
| `PackManifestFetcher` | Fetches server manifest | **Deleted** |
| `PackRefreshWorker` | Periodic manifest refresh | **Deleted** |
| `LanguageCatalog` | (didn't exist) | **New** — hardcoded list of supported languages |
| `PackDownloader` | Download pre-built `.db`, verify SHA-256 | Download JSONL, stream-parse, write SQLite |
| `PackInfo` model | Has `sha256` field | `sha256` removed; `displayName` added |
| `PackManifest` model | Used by manifest fetcher | **Deleted** |
| `DictionaryPacksViewModel` | `StateFlow<PackManifest?>` + `manifestError` | Just `LanguageCatalog.all` — no network, no error state |
| `DictionaryPacksScreen` | "Available" from server manifest | "Available" from `LanguageCatalog`; installed packs get "Update" button |
| `EpubReaderViewModel` | Fetches manifest for `NOT_INSTALLED` pack info | Reads from `LanguageCatalog` for pack info |
| `DICT_MANIFEST_URL` | `BuildConfig` field | **Deleted** |

Everything else is unchanged: Room entities/DAOs, migration, `DictionaryPackSqliteStore`, `WordLookupRepositoryImpl`, `EpubReaderViewModel` lookup state machine, `WordLookupSheet`, `LookupUiState`.

---

## Data source — kaikki.org

[kaikki.org](https://kaikki.org/dictionary/) distributes Wiktionary as JSONL — one JSON object per line, one file per language. These are English Wiktionary entries: foreign-language words with English glosses. That is exactly the use case: user reads a French book, looks up a French word, gets English definitions.

**URL pattern:**
```
https://kaikki.org/dictionary/<EnglishName>/kaikki.org-dictionary-<EnglishName>.json
```

**Per-line format (relevant fields only):**
```json
{
  "word": "chat",
  "pos": "noun",
  "lang": "French",
  "lang_code": "fr",
  "senses": [
    { "glosses": ["cat (animal)"] },
    { "glosses": ["chat (internet)"] }
  ]
}
```

**Extraction rules:**
- `form` ← `word`
- `pos` ← `pos` (values: `"noun"`, `"verb"`, `"adj"`, `"adv"`, `"prep"`, etc.)
- `glosses` ← JSON array of `senses[i].glosses[0]` for each sense that has a `glosses` list with at least one element; senses with empty `glosses` are skipped

**Conflict resolution:** `INSERT OR REPLACE` on `(form, pos)` — last occurrence wins. Wiktionary entries for the same form+pos are typically minor variants of each other.

**SQLite schema** (unchanged from original spec):
```sql
CREATE TABLE entries (
  form    TEXT NOT NULL,
  pos     TEXT NOT NULL DEFAULT '',
  glosses TEXT NOT NULL,
  PRIMARY KEY (form, pos)
);
CREATE INDEX entries_form ON entries(form COLLATE NOCASE);
```

---

## Language catalog

Hardcoded in `core/data` as `LanguageCatalog.kt`. Each entry is a `LanguageCatalogEntry`:

```kotlin
// in core:dictionary
data class LanguageCatalogEntry(
    val languageTag: String,       // BCP-47 primary subtag, e.g. "fr"
    val displayName: String,       // English name, e.g. "French"
    val jsonlUrl: String,          // kaikki.org download URL
    val approximateSizeBytes: Long, // for UI display before download
    val attributionHtml: String,
    val licenseUrl: String,
)
```

Initial catalog (~15 languages with strong Wiktionary coverage):

| Language | Tag | English name in URL |
|----------|-----|---------------------|
| French | fr | French |
| German | de | German |
| Spanish | es | Spanish |
| Italian | it | Italian |
| Portuguese | pt | Portuguese |
| Dutch | nl | Dutch |
| Russian | ru | Russian |
| Japanese | ja | Japanese |
| Chinese | zh | Chinese |
| Korean | ko | Korean |
| Arabic | ar | Arabic |
| Latin | la | Latin |
| Turkish | tr | Turkish |
| Polish | pl | Polish |
| Swedish | sv | Swedish |

`attributionHtml` for all entries:
```
Data from <a href="https://en.wiktionary.org">Wiktionary</a>
```
`licenseUrl` for all entries: `"https://creativecommons.org/licenses/by-sa/3.0/"`

`LanguageCatalog` is a singleton object:
```kotlin
object LanguageCatalog {
    val all: List<LanguageCatalogEntry> = listOf(...)

    fun entryFor(languageTag: String): LanguageCatalogEntry? =
        all.firstOrNull { it.languageTag == languageTag }
}
```

`LanguageCatalog` lives in `core:dictionary` (pure-Kotlin, no Android imports) so it is accessible from both `core:data` and `app`.

---

## `PackInfo` model change

Remove `sha256`. Add `displayName`.

```kotlin
data class PackInfo(
    val languageTag: String,
    val displayName: String,   // NEW — "French", "German", etc.
    val downloadUrl: String,   // now points to JSONL, not a pre-built .db
    val approximateSizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
)
```

`PackInfo` is constructed from `LanguageCatalogEntry` wherever needed. `PackManifest` is deleted.

---

## `PackDownloader` — new implementation

Replace the current implementation entirely. The signature `suspend fun download(entry: LanguageCatalogEntry): Boolean` (or keep `PackInfo` as the param — either works; `LanguageCatalogEntry` preferred since it's the authoritative source).

Steps:

1. **Upsert DOWNLOADING row** in Room (same as before).
2. **Download JSONL** via Ktor streaming into a `ByteReadChannel`. The file is not gzipped (kaikki.org serves plain JSON). Write raw bytes to `<dictsDir>/<lang>.tmp.json`.
3. **Build SQLite**: open a new SQLite database at `<dictsDir>/<lang>.tmp.db` using `android.database.sqlite.SQLiteDatabase.openOrCreateDatabase`. Create the `entries` table and index. Run a single `beginTransaction` / `setTransactionSuccessful` / `endTransaction` block. Inside, stream-read `<lang>.tmp.json` line by line using `BufferedReader`, parse each line with `org.json.JSONObject`, extract `form`, `pos`, `glosses`, and execute a prepared `INSERT OR REPLACE`.
4. **Delete** `<lang>.tmp.json`.
5. **Rename** `<lang>.tmp.db` → `<lang>.db` (atomic on Linux/Android). If rename fails, delete tmp and mark FAILED.
6. **Upsert INSTALLED row** in Room with `installedAt = clock.nowMs()`, `sizeBytes = finalFile.length()`, `packVersion = ISO date of today` (e.g. `"2026-08-17"`).

On any exception: delete both tmp files, call `updateState(FAILED)`, return false.

**No SHA-256 check** — we built the file ourselves from a trusted source.

**JSON parsing:** use `org.json.JSONObject` (already on the Android classpath, no new dependency). Do NOT use Gson, Moshi, or kotlinx.serialization for the per-line parse — `org.json` is the lightest option for streaming line-by-line.

**Batch size:** commit every 10 000 rows in a new transaction. One giant transaction risks OOM on the journal; 10k-row batches balance write speed against memory. Wrap the outer loop in a transaction manager that commits every 10k inserts and opens a new transaction for the next batch.

**Performance expectation:** ~30–90 seconds for French (≈800k entries) on a mid-range device. All runs in `PackDownloadWorker` (WorkManager), so the user is never blocked.

---

## Update flow

"Update" is identical to first install: re-run the download+convert pipeline for the same `LanguageCatalogEntry`. The old `.db` stays readable until the atomic rename replaces it. Room state goes `INSTALLED → DOWNLOADING → INSTALLED`.

The `DictionaryPacksScreen` shows an "Update" button for every installed pack (not just outdated ones — we have no version information from kaikki.org). Tapping it re-enqueues the download worker.

---

## Deleted components

| File | Test file | Removed-test trailers needed |
|------|-----------|------------------------------|
| `core/data/…/PackManifestFetcher.kt` | `PackManifestFetcherTest.kt` | `Removed-test: manifest is parsed correctly from valid JSON response` `Removed-test: IOException is thrown on HTTP error response` |
| `app/…/PackRefreshWorker.kt` | none | — |
| `core/dictionary/…/PackManifest.kt` | none | — |

The `DictionaryModule` in `core:data` loses the `PackManifestFetcher` binding and the `@Named("dictManifestUrl")` string provision. The `AppModule` in `app` loses the `provideDictManifestUrl` method. `DICT_MANIFEST_URL` is removed from `app/build.gradle.kts`.

---

## `DictionaryPacksViewModel` changes

```kotlin
@HiltViewModel
class DictionaryPacksViewModel @Inject constructor(
    private val packStore: PackStore,
    private val scheduler: DictionaryPackScheduler,
    // PackManifestFetcher REMOVED
) : ViewModel() {

    val catalog: List<LanguageCatalogEntry> = LanguageCatalog.all  // constant, no StateFlow needed

    val installedPacks: StateFlow<List<InstalledPack>> = ...  // unchanged

    // refreshManifest(), manifest, manifestError ALL REMOVED

    fun enqueueDownload(context: Context, entry: LanguageCatalogEntry) {
        scheduler.enqueueDownload(context, entry)
    }

    fun enqueueUpdate(context: Context, languageTag: String) {
        LanguageCatalog.entryFor(languageTag)?.let { scheduler.enqueueDownload(context, it) }
    }

    fun deleteInstalledPack(languageTag: String) {
        viewModelScope.launch { packStore.deleteInstalledPack(languageTag) }
    }
}
```

---

## `DictionaryPacksScreen` changes

- **"Available" section:** iterate `catalog.filter { it.languageTag !in installedTags }`. No loading spinner, no error state, no Retry button. The catalog is always available instantly.
- **"Installed" section:** each row gains an **"Update"** button alongside the existing "Delete" button.
- `AvailablePackRow` uses `entry.displayName` and `entry.approximateSizeBytes`.
- `InstalledPackRow` unchanged otherwise.

---

## `EpubReaderViewModel` changes

`observeLookupResult` currently injects `PackManifestFetcher` to get `PackInfo` for the `NOT_INSTALLED` state. Replace that with a `LanguageCatalog.entryFor(languageTag)` lookup — synchronous, no try/catch needed.

`EpubReaderViewModel` loses its `PackManifestFetcher` constructor parameter.

---

## `DictionaryPackScheduler` changes

`enqueueDownload` signature changes from `(Context, PackInfo)` to `(Context, LanguageCatalogEntry)`. The `Data` passed to the worker encodes the entry's fields (languageTag + jsonlUrl + displayName + etc.). `PackDownloadWorker` reads the same fields from `Data`.

---

## Testing

**`PackDownloaderTest`** — rewrite:
- Use `MockWebServer` to serve a small valid JSONL (3–5 entries) and verify the resulting `.db` is queryable.
- One test: JSONL with duplicate `(form, pos)` — verify `INSERT OR REPLACE` produces one row.
- One test: HTTP 500 — verify FAILED state and no `.db` file left.
- One test: malformed JSON line — verify the line is skipped and the pack still installs (best-effort).

**`LanguageCatalogTest`** — simple:
- All entries have non-blank `languageTag`, `displayName`, `jsonlUrl`.
- `jsonlUrl` contains the language tag string.
- `entryFor("fr")` returns the French entry.

**`PackManifestFetcherTest`** — deleted (see trailers above).

**`DictionaryPacksViewModelTest`** — update:
- Remove `manifest loaded on init` and `manifestError on fetch fail` tests (concept gone).
- Add `catalog returns all entries` test.
- Add `enqueueUpdate delegates to scheduler for installed language` test.
