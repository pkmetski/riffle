package com.riffle.core.data

import com.riffle.core.domain.AbsWebSourceDescriptor

/**
 * Classifier for the pre-`abs_` ABS annotation-file layout.
 *
 * Before [AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX] existed, ABS annotation files on the
 * WebDAV share (and in [LocalDirectoryTarget]) named their namespace segment with the raw
 * `/api/me` user id — a bare UUID with no source-tag prefix. After the switch, ABS behaves
 * symmetrically with Komga: `abs_<uuid>` on the wire.
 *
 * Legacy files still exist on every user's share and must remain readable, so both targets run
 * their listing through [migratedName] and MOVE-rename (WebDAV) or dir-rename (local) any hits.
 * The classifier is pure Kotlin so it can be unit-tested at the JVM level without touching a
 * real WebDAV server.
 */
internal object LegacyAbsNamespaceMigration {

    /**
     * True if [physicalName]'s namespace segment (`<ns>__…`) is a bare UUID — i.e. a legacy
     * ABS file that predates [AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX]. Files already
     * prefixed with any known source tag (`abs_`, `komga_`) or with a non-UUID first segment
     * are left alone, so unrelated content on the share never gets accidentally rewritten.
     */
    fun isLegacyAbsFilename(physicalName: String): Boolean {
        val sepIdx = physicalName.indexOf(NAMESPACE_SEPARATOR)
        if (sepIdx <= 0) return false
        val nsSegment = physicalName.substring(0, sepIdx)
        return isLegacyAbsNamespaceSegment(nsSegment)
    }

    /**
     * True if [nsSegment] is a bare UUID (matches [UUID_REGEX]) and doesn't already start with
     * a known source-tag prefix. Kept public-to-the-package so [LocalDirectoryTarget] can use
     * the same predicate on directory names.
     */
    fun isLegacyAbsNamespaceSegment(nsSegment: String): Boolean {
        if (nsSegment.startsWith(AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX)) return false
        if (nsSegment.startsWith(com.riffle.core.domain.KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX)) return false
        return UUID_REGEX.matches(nsSegment)
    }

    /**
     * Return the post-migration filename for [physicalName], or [physicalName] unchanged when
     * no rewrite applies. Idempotent: calling twice yields the same value.
     */
    fun migratedName(physicalName: String): String {
        if (!isLegacyAbsFilename(physicalName)) return physicalName
        return AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX + physicalName
    }

    private const val NAMESPACE_SEPARATOR = "__"
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
