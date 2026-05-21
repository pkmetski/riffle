# Riffle

An Android ebook reader for [Audiobookshelf](https://www.audiobookshelf.org/) self-hosted servers.

Riffle lets you browse your ABS ebook library, read EPUB and PDF files, and sync reading progress — all from a clean, privacy-respecting Android app.

## Features

- [x] Project scaffold & CI pipeline ([#3](https://github.com/pkmetski/riffle/issues/3))
- [x] Server login & management ([#4](https://github.com/pkmetski/riffle/issues/4))
- [x] Library browsing — flat Library Item list ([#5](https://github.com/pkmetski/riffle/issues/5))
- [x] Series & Collections hierarchy ([#6](https://github.com/pkmetski/riffle/issues/6))
- [x] EPUB reader — open & navigate ([#7](https://github.com/pkmetski/riffle/issues/7))
- [ ] Autonomous Test Harness ([#8](https://github.com/pkmetski/riffle/issues/8))
- [ ] PDF reader — open & navigate ([#9](https://github.com/pkmetski/riffle/issues/9))
- [x] Reading Session & outbound Progress Sync ([#10](https://github.com/pkmetski/riffle/issues/10))
- [ ] Inbound Progress Sync & conflict resolution ([#11](https://github.com/pkmetski/riffle/issues/11))
- [ ] Offline Mode & Downloads ([#12](https://github.com/pkmetski/riffle/issues/12))
- [x] EPUB Formatting Preferences ([#13](https://github.com/pkmetski/riffle/issues/13))
- [ ] PDF Formatting Preferences ([#14](https://github.com/pkmetski/riffle/issues/14))
- [x] Table of Contents navigation ([#15](https://github.com/pkmetski/riffle/issues/15))
- [ ] Chapter Navigation Rail ([#16](https://github.com/pkmetski/riffle/issues/16))
- [ ] EPUB Highlights & Notes ([#17](https://github.com/pkmetski/riffle/issues/17))
- [ ] Bookmarks (EPUB & PDF) ([#18](https://github.com/pkmetski/riffle/issues/18))
- [ ] Annotations panel ([#19](https://github.com/pkmetski/riffle/issues/19))
- [ ] Reading Statistics screen ([#20](https://github.com/pkmetski/riffle/issues/20))
- [x] Crash Reporter (ACRA) ([#21](https://github.com/pkmetski/riffle/issues/21))
- [ ] Screen Wake Lock ([#22](https://github.com/pkmetski/riffle/issues/22))

## Requirements

- Android 7.0 (API 24) or higher
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
