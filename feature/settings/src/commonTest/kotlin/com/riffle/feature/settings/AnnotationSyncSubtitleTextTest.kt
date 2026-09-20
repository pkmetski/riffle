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

    @Test fun notConfiguredNamesWhatIsLost() {
        assertEquals(
            "Not configured · Komga annotations, web-source reading progress",
            AnnotationSyncSubtitle.NotConfigured.label(),
        )
    }

    @Test fun waitingForFirstSync() {
        assertEquals("Waiting for first sync…", AnnotationSyncSubtitle.WaitingForFirstSync.label())
    }

    @Test fun authFailedTellsTheUserWhatToDo() {
        assertEquals(
            "Authentication failed · tap to re-enter credentials",
            AnnotationSyncSubtitle.AuthFailed.label(),
        )
    }

    @Test fun tlsErrorTellsTheUserWhatToDo() {
        assertEquals("TLS error · tap to check server URL", AnnotationSyncSubtitle.TlsError.label())
    }

    @Test fun httpErrorInterpolatesTheStatusCode() {
        assertEquals(
            "Source error (HTTP 503) · will retry automatically",
            AnnotationSyncSubtitle.HttpError(503).label(),
        )
    }

    @Test fun syncFailedSaysItWillRetry() {
        assertEquals("Sync failed · will retry automatically", AnnotationSyncSubtitle.SyncFailed.label())
    }

    @Test fun booksPendingInterpolatesTheCount() {
        assertEquals(
            "3 book(s) pending · will sync when online",
            AnnotationSyncSubtitle.BooksPendingOffline(3).label(),
        )
    }

    @Test fun offlineSaysItWillSyncWhenConnected() {
        assertEquals("Offline · will sync when connected", AnnotationSyncSubtitle.Offline.label())
    }

    @Test fun syncedShowsTheIdentity() {
        assertEquals("Synced · me@host", AnnotationSyncSubtitle.Synced("me@host").label())
    }

    /**
     * There is no identity to name, so there is nothing for the separator to separate. This used
     * to assert `"Synced · "` — the trailing bullet Android's `stringResource(ui_synced_identity,
     * identity ?: "")` produces — on the grounds that iOS should match Android exactly. Matching
     * a formatting wart is not parity, it is two platforms sharing a defect, so both sides now
     * drop the stranded separator via [withoutDanglingSeparator].
     */
    @Test fun syncedWithoutAnIdentityStillReadsAsSynced() {
        assertEquals("Synced", AnnotationSyncSubtitle.Synced(null).label())
    }

    @Test fun aDanglingSeparatorIsOnlyDroppedWhenTheArgumentWasEmpty() {
        assertEquals("Synced · me@host", "Synced · me@host".withoutDanglingSeparator())
        assertEquals("Синхронизирано", "Синхронизирано · ".withoutDanglingSeparator())
    }
}
