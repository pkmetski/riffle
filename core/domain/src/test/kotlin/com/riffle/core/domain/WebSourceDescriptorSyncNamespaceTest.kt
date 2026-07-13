package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for #529. Every [WebSourceDescriptor] answers [WebSourceDescriptor
 * .syncNamespaceFor] deterministically per source kind so a new server-backed source can be
 * added by overriding the hook — no changes to the sync controller, the sweep, or the reader.
 *
 * Would-fail-if-reverted assertions:
 *  - ABS Audiobookshelf with a persisted `absUserId` → `Configured(<id>)` (raw, no prefix)
 *  - ABS Audiobookshelf without an `absUserId` → `PendingRemoteId` (repo triggers /api/me)
 *  - ABS Storyteller peer → `LocalOnly` (never opens a WebDAV namespace of its own)
 *  - Komga with a persisted id → `Configured("komga_<id>")` (prefixed so it can't collide with ABS)
 *  - Komga without an id → `PendingRemoteId`
 *  - Chitanka / Gutenberg / LocalFiles → `LocalOnly` (anonymous / local; UI surfaces the reason)
 */
class WebSourceDescriptorSyncNamespaceTest {

    private fun absSource(userId: String? = null, serverType: ServerType = ServerType.AUDIOBOOKSHELF): Source =
        Source(
            id = "src-1",
            url = SourceUrl.parse("https://abs.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = SourceType.ABS,
            serverType = serverType,
            absUserId = userId,
        )

    private fun komgaSource(userId: String? = null): Source =
        Source(
            id = "src-2",
            url = SourceUrl.parse("https://komga.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = SourceType.KOMGA,
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = userId,
        )

    @Test
    fun `ABS Audiobookshelf with persisted user id resolves to Configured with the raw id`() {
        val ns = AbsWebSourceDescriptor.syncNamespaceFor(absSource(userId = "abs-user-42"))
        // Kept raw (no `abs_` prefix) so existing installs' WebDAV files stay addressable
        // without a rewrite migration.
        assertEquals(SyncNamespace.Configured("abs-user-42"), ns)
    }

    @Test
    fun `ABS Audiobookshelf without a persisted user id resolves to PendingRemoteId`() {
        val ns = AbsWebSourceDescriptor.syncNamespaceFor(absSource(userId = null))
        assertEquals(SyncNamespace.PendingRemoteId, ns)
    }

    @Test
    fun `ABS Storyteller peer resolves to LocalOnly regardless of user id`() {
        // Storyteller peers piggyback on the paired Audiobookshelf server — they never open a
        // WebDAV namespace of their own. Even a stray absUserId on the row shouldn't flip them
        // to Configured; the ServerType is the discriminator.
        val ns = AbsWebSourceDescriptor.syncNamespaceFor(
            absSource(userId = "stray-id", serverType = ServerType.STORYTELLER_SERVICE),
        )
        assertTrue(ns is SyncNamespace.LocalOnly)
    }

    @Test
    fun `Komga with persisted user id resolves to Configured with a komga prefix`() {
        val ns = KomgaWebSourceDescriptor.syncNamespaceFor(komgaSource(userId = "komga-uid-abc"))
        // Reference the descriptor's own const so a rename/typo trips a compile-time diff, not
        // a silent round-trip via a mirrored string literal (AGENTS.md: never the literal).
        val expected = SyncNamespace.Configured(
            "${KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX}komga-uid-abc",
        )
        assertEquals(expected, ns)
        // Sanity: the prefix hasn't drifted to a subtly different value.
        assertEquals("komga_", KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX)
    }

    @Test
    fun `Komga without a persisted user id resolves to PendingRemoteId`() {
        val ns = KomgaWebSourceDescriptor.syncNamespaceFor(komgaSource(userId = null))
        assertEquals(SyncNamespace.PendingRemoteId, ns)
    }

    @Test
    fun `Chitanka Gutenberg LocalFiles all resolve to LocalOnly`() {
        val src = absSource().copy(type = SourceType.LOCAL_FILES)
        assertTrue(LocalFilesWebSourceDescriptor.syncNamespaceFor(src) is SyncNamespace.LocalOnly)
        assertTrue(ChitankaWebSourceDescriptor.syncNamespaceFor(src) is SyncNamespace.LocalOnly)
        assertTrue(GutenbergWebSourceDescriptor.syncNamespaceFor(src) is SyncNamespace.LocalOnly)
    }
}
