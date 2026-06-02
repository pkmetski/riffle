# Riffle

An Android ebook reader for [Audiobookshelf](https://www.audiobookshelf.org/) and [Storyteller](https://storyteller-platform.gitlab.io/storyteller) self-hosted servers.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/pkmetski)

Riffle lets you browse your ebook library, read EPUB and PDF files, and sync reading progress — all from a clean, privacy-respecting Android app.

## Features

### Reading
- EPUB and PDF readers
- Table of Contents navigation
- In-book text search
- Chapter map progress indicator
- Fullscreen immersive reading mode

### Reading Display
- Rich formatting controls (themes, fonts, sizing, spacing, margins, justification)
- Auto theme that switches between configured day and night themes on a global clock schedule
- Paginated and continuous scroll modes, with landscape double-page spread
- Per-book formatting overrides
- Volume-key page navigation
- Keep screen on

### Library
- Multi-server support with library visibility controls
- Browse by Home, To Read, Series, Collections, and All Books
- Plex-style cover grid with book details
- Read/unread and "To Read" toggles
- Full-text library search

### Downloads & Offline
- Download for offline reading, plus automatic caching on open
- Downloads manager with bulk removal
- Offline detection and seamless offline reading

### Server & Sync
- Audiobookshelf and Storyteller login with secure token storage and insecure-connection warnings
- Storyteller Readaloud Library: browse every completed readaloud as a single library
- Readaloud playback in the reader: synced sentence highlight, auto-page-turn, "Play from here", background playback with lock-screen/Bluetooth controls, and a per-server audio cache cap
- Bidirectional progress sync with last-update-wins conflict resolution
- Periodic auto-sync and offline queueing
- Reading session time tracking

## Requirements

- Android 7.0 (API 24) or higher
- A running [Audiobookshelf](https://www.audiobookshelf.org/) or [Storyteller](https://storyteller-platform.gitlab.io/storyteller) server

## Distribution

| Channel | Status |
|---------|--------|
| Google Play Store | Planned |
| F-Droid | Planned |
| Codeberg Releases | CI-built signed APK |

## Architecture

Riffle follows a strict layered architecture designed for future Kotlin Multiplatform (KMP) migration:

```
app/                  # Android UI — Jetpack Compose + Hilt
core/domain/          # Pure Kotlin — entities, repository interfaces, domain logic
core/network/         # Pure Kotlin — OkHttp-based ABS API client
core/database/        # Android — Room database
core/data/            # Android — repository implementations, Keystore token storage
```

## Development

### Prerequisites

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
