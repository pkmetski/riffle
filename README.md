# Riffle

An Android ebook reader for [Audiobookshelf](https://www.audiobookshelf.org/) self-hosted servers.

Riffle lets you browse your ABS ebook library, read EPUB and PDF files, and sync reading progress — all from a clean, privacy-respecting Android app.

## Features

- Connect to one or more self-hosted Audiobookshelf servers
- Browse ebook libraries (EPUB and PDF)
- Read with the [Readium Kotlin SDK](https://readium.org/kotlin-toolkit/)
- Bidirectional reading progress sync
- EPUB formatting preferences (font, theme, spacing)
- Highlights, notes, and bookmarks
- Offline reading via automatic cache or explicit downloads
- No Firebase, no proprietary SDKs — F-Droid compatible

## Requirements

- Android 8.0 (API 26) or higher
- A running [Audiobookshelf](https://www.audiobookshelf.org/) server

## Distribution

| Channel | Status |
|---------|--------|
| Google Play Store | Planned |
| F-Droid | Planned |
| GitHub Releases | CI-built signed APK |

## Architecture

Riffle follows a strict layered architecture designed for future Kotlin Multiplatform (KMP) migration:

```
app/                  # Android UI — Jetpack Compose + Hilt
core/domain/          # Pure Kotlin — entities, repository interfaces, domain logic
core/network/         # Pure Kotlin — OkHttp-based ABS API client
core/database/        # Android — Room database
core/data/            # Android — repository implementations, Keystore token storage
```

Key decisions:
- **Domain and data layers** are pure Kotlin with no Android imports — KMP-ready
- **Android Keystore** stores session tokens (never SharedPreferences or plain DataStore)
- **ACRA** for crash reporting (no Firebase / Crashlytics)
- See [`docs/adr/`](docs/adr/) for full architecture decision records

## Development

### Prerequisites

- macOS with Homebrew (for bootstrap)
- Android SDK (via Android Studio or `sdkmanager`)

### Bootstrap

```sh
make bootstrap   # Install JDK 17, download Gradle wrapper, fetch bundled fonts
```

### Build

```sh
make build       # Assemble debug APK
make test        # Run unit tests
make check       # Full CI check: build + lint + tests
make install     # Build and install debug APK on connected device
```

## Glossary

See [`CONTEXT.md`](CONTEXT.md) for the full domain glossary (Server, Library, Library Item, Cache, Download, Reading Session, Progress Sync, etc.).
