# Consolidate `trustAllCerts` into a shared TLS helper

Closes #379 (partial — see "Scope" below).

## Problem

`OkHttpClient.trustAllCerts()` is copy-pasted verbatim in **five** network files under `core/network`:

| File | Callsite line(s) |
| --- | --- |
| `AbsApiClient.kt` | 615, 626 |
| `StorytellerApiClient.kt` | 134, 145 |
| `StorytellerPositionApi.kt` | 56, 91, 111 |
| `StorytellerBundleApi.kt` | 87, 121, 141 |
| `AudiobookBundleApi.kt` | 58, 92 |

Each copy is the same ~13-line `X509TrustManager` + `SSLContext("TLS")` boilerplate. Changing cert policy (e.g. to allow-list a CA) requires editing five files in parallel. DIP + OCP concern.

## Why the issue's other recommendations do not ship in this PR

The issue proposed three deepenings; only the SSL consolidation survives scrutiny:

1. **Collapse `AbsApi*` interfaces into one narrow public interface.** — The five interfaces (`AbsApi`, `AbsLibraryApi`, `AbsSessionApi`, `AbsServerInfoApi`, `AbsPlaybackApi`, `AbsBookmarkApi`) are already used *narrowly* by every consumer (see `AudiobookRepositoryImplTest.kt`, `ServerRepositoryTest.kt`, `LibraryRepositoryTest.kt`, etc. — repositories depend on the narrow surface, not on `AbsApiClient`). This is the ISP-correct shape already; collapsing would be a regression.
2. **Extract `ServerHttpClientFactory` and inject via DI.** — YAGNI. The insecure-TLS wrap is the only shared concern; timeouts/interceptors are per-client and diverge (bundle client has zero read timeout, sidecar has a bounded call timeout). A single extension function is the seam.
3. **Extract `ServerAuthenticator` interface.** — YAGNI. Two call-sites (ABS JSON+POST /login, Storyteller multipart + different error-code mapping); no third planned. Introducing an interface for two shape-different implementations adds indirection without leverage.

## Design

### New file

`core/network/src/main/kotlin/com/riffle/core/network/InsecureTls.kt`:

```kotlin
package com.riffle.core.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Returns a copy of this client that accepts self-signed / untrusted TLS certificates.
 * Riffle exposes this per-server via the `insecureAllowed` flag (self-hosted homelab servers
 * often serve behind self-signed certs).
 */
internal fun OkHttpClient.withInsecureTls(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .build()
}
```

### Callsite migration

At each of the five files:

- Delete the `private fun OkHttpClient.trustAllCerts()` body (~13 lines each).
- Delete the now-unused `SecureRandom`, `X509Certificate`, `SSLContext`, `X509TrustManager` imports.
- Rename `client.trustAllCerts()` → `client.withInsecureTls()`.

Naming: `withInsecureTls` reads more accurately than `trustAllCerts` at the callsite (the boolean flag is already named `insecureAllowed`), and the site remains conditional (`if (insecureAllowed) client.withInsecureTls() else client`).

### Test

`core/network/src/test/kotlin/com/riffle/core/network/InsecureTlsTest.kt`:

- Spin up an OkHttp `MockWebServer` with a self-signed TLS cert.
- Verify a plain `OkHttpClient` fails against it (control).
- Verify `.withInsecureTls()` succeeds.
- Verify the original client is unchanged (functional call, returns a new client).

If MockWebServer TLS setup is too heavy for one test, fall back to asserting the returned client's `sslSocketFactory` differs and that its wrapped `X509TrustManager` accepts a self-signed chain.

## Non-goals

- No behavior change. Every callsite receives an equivalent client.
- No public-API change on `AbsApiClient`, `StorytellerApiClient`, or any `AbsApi*` interface.
- No change to DI wiring (`NetworkModule`) — none of the shared client is a bean; the extension is invoked lazily inside each client class exactly as before.
- No dev-server / AVD verification required — pure JVM refactor with a JVM unit test.

## Verification

- `./gradlew :core:network:test` green.
- `./gradlew test` green across affected modules (network, data).
- Grep confirms zero occurrences of `trustAllCerts` and `X509TrustManager` outside `InsecureTls.kt` after the change.
