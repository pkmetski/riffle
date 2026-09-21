package com.riffle.feature.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Outcome of one cover fetch, reported to [LocalCoverLoadReporter].
 *
 * The Android app logs these to `LogChannel.Covers` (`adb logcat -d | grep RIFFLE_COVERS`) to tell
 * "the cover never loaded" apart from "the cover loaded from the network every time". That
 * instrumentation used to be an `ImageRequest.Builder.instrumentCover(…)` extension inside
 * `app`; keeping it as a reporter seam means the shared [CoverImage] can carry it without
 * dragging `android.util.Log` into `commonMain`, and iOS can opt in later without a second
 * request-builder.
 */
fun interface CoverLoadReporter {
    /**
     * @param kind what is being covered — `"item"`, `"series"`, `"collection"`.
     * @param key the entity id, for correlating with a library dump.
     * @param url the cover URL that was requested.
     * @param hit true when the image resolved, false when it failed.
     * @param detail the Coil data source on a hit, the throwable's simple name on a miss.
     */
    fun report(kind: String, key: String?, url: String?, hit: Boolean, detail: String?)
}

/** Host-provided cover instrumentation; null (the default) means no reporting. */
val LocalCoverLoadReporter = staticCompositionLocalOf<CoverLoadReporter?> { null }

/**
 * A remote cover image, drawn over [DefaultCoverPlaceholder] so an item with no artwork — or one
 * whose fetch is still in flight or has failed — still shows the gradient rather than a hole.
 *
 * **This is the composable iOS did not have.** `shared/src/commonMain` had zero `AsyncImage` uses
 * against 27 in `app`, so every tile, row and hero on iOS was the procedural placeholder even
 * though `MainViewController` already registers the Coil loader and `feature:source-ui` already
 * depends on `coil.compose`. Sharing the renderer is what fixes that everywhere at once.
 *
 * [token] is the source's stored credential; it is turned into an `Authorization` header by
 * [asAuthHeader], which passes Komga's full `Basic …` value through untouched and wraps an opaque
 * Audiobookshelf/Storyteller token in `Bearer`. An empty token sends no header at all.
 */
@Composable
fun CoverImage(
    url: String?,
    token: String,
    contentDescription: String?,
    isAudiobook: Boolean,
    modifier: Modifier = Modifier,
    instrumentationKind: String = "item",
    instrumentationKey: String? = null,
) {
    // A Box, not two siblings: the artwork is drawn *over* the placeholder, so a caller that is
    // a Column or a Row would otherwise stack them instead of layering them.
    Box(modifier = modifier) {
        DefaultCoverPlaceholder(isAudiobook = isAudiobook, modifier = Modifier.fillMaxSize())
        if (!url.isNullOrBlank()) {
            RemoteCover(url, token, contentDescription, instrumentationKind, instrumentationKey)
        }
    }
}

@Composable
private fun RemoteCover(
    url: String,
    token: String,
    contentDescription: String?,
    instrumentationKind: String,
    instrumentationKey: String?,
) {
    val reporter = LocalCoverLoadReporter.current
    val header = token.asAuthHeader()
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .apply {
                if (header.isNotEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().add("Authorization", header).build())
                }
            }
            .crossfade(true)
            .apply {
                if (reporter != null) {
                    listener(
                        onSuccess = { _, result ->
                            reporter.report(
                                instrumentationKind,
                                instrumentationKey,
                                url,
                                hit = true,
                                detail = result.dataSource.toString(),
                            )
                        },
                        onError = { _, result ->
                            reporter.report(
                                instrumentationKind,
                                instrumentationKey,
                                url,
                                hit = false,
                                detail = coverErrorDetail(result.throwable),
                            )
                        },
                    )
                }
            }
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The `err=` value the Android cover log has always carried: the throwable's simple class name.
 *
 * `Throwable::class.simpleName` is `null` for an anonymous/local class on Kotlin/Native as well as
 * on the JVM, so fall back to the qualified rendering rather than logging `err=null`.
 */
internal fun coverErrorDetail(throwable: Throwable): String =
    throwable::class.simpleName ?: throwable.toString().substringBefore(':')
