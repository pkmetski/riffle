package com.riffle.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wording of every [AnnotationSyncSubtitle] branch.
 *
 * Seven of these nine strings differed between the two platforms before they were collapsed onto
 * one derivation, and nothing failed — Android resolved the `stringResource`, iOS resolved a
 * private `when`, and each suite was green against its own copy. These assertions are the values
 * `app/src/main/res/values/strings.xml` carries; `AnnotationSyncStringsParityTest` in `:app`
 * checks the resource file still agrees.
 */
class AnnotationSyncSubtitleTextTest {

    private val anyNow = 1_000_000L

    @Test fun notConfiguredNamesWhatIsLost() {
        assertEquals(
            "Not configured · Komga annotations, web-source reading progress",
            AnnotationSyncSubtitle.NotConfigured.label(anyNow),
        )
    }

    @Test fun waitingForFirstSync() {
        assertEquals("Waiting for first sync…", AnnotationSyncSubtitle.WaitingForFirstSync.label(anyNow))
    }

    @Test fun authFailedTellsTheUserWhatToDo() {
        assertEquals(
            "Authentication failed · tap to re-enter credentials",
            AnnotationSyncSubtitle.AuthFailed.label(anyNow),
        )
    }

    @Test fun tlsErrorTellsTheUserWhatToDo() {
        assertEquals("TLS error · tap to check server URL", AnnotationSyncSubtitle.TlsError.label(anyNow))
    }

    @Test fun httpErrorInterpolatesTheStatusCode() {
        assertEquals(
            "Source error (HTTP 503) · will retry automatically",
            AnnotationSyncSubtitle.HttpError(503).label(anyNow),
        )
    }

    @Test fun syncFailedSaysItWillRetry() {
        assertEquals("Sync failed · will retry automatically", AnnotationSyncSubtitle.SyncFailed.label(anyNow))
    }

    @Test fun booksPendingInterpolatesTheCount() {
        assertEquals(
            "3 book(s) pending · will sync when online",
            AnnotationSyncSubtitle.BooksPendingOffline(3).label(anyNow),
        )
    }

    @Test fun offlineSaysItWillSyncWhenConnected() {
        assertEquals("Offline · will sync when connected", AnnotationSyncSubtitle.Offline.label(anyNow))
    }

    @Test fun syncedShowsRelativeTimeJustNow() {
        val nowMs = 1_000_000L
        assertEquals("Synced · just now", AnnotationSyncSubtitle.Synced(nowMs - 30_000L).label(nowMs))
    }

    @Test fun syncedShowsRelativeTimeMinutesAgo() {
        val nowMs = 1_000_000L
        assertEquals("Synced · 5 min ago", AnnotationSyncSubtitle.Synced(nowMs - 300_000L).label(nowMs))
    }

    @Test fun syncedShowsRelativeTimeHoursAgo() {
        val nowMs = 1_000_000L
        assertEquals("Synced · 2 h ago", AnnotationSyncSubtitle.Synced(nowMs - 7_200_000L).label(nowMs))
    }

    @Test fun syncedShowsRelativeTimeDaysAgo() {
        val nowMs = 1_000_000_000L
        assertEquals("Synced · 3 d ago", AnnotationSyncSubtitle.Synced(nowMs - 3 * 86_400_000L).label(nowMs))
    }

    /**
     * When lastSyncMs is null (never synced), the separator is dropped so the row reads "Synced"
     * rather than "Synced · ".
     */
    @Test fun syncedWithNullLastSyncMsStillReadsAsSynced() {
        assertEquals("Synced", AnnotationSyncSubtitle.Synced(null).label(nowMs = 1_000_000L))
    }

    @Test fun aDanglingSeparatorIsOnlyDroppedWhenTheArgumentWasEmpty() {
        assertEquals("Synced · just now", "Synced · just now".withoutDanglingSeparator())
        assertEquals("Синхронизирано", "Синхронизирано · ".withoutDanglingSeparator())
    }
}
