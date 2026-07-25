package com.riffle.core.sources.webdav

import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.KomgaWebSourceDescriptor

/**
 * Classifier for the pre-`abs_` ABS annotation-file layout.
 *
 * Before [AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX] existed, ABS annotation files on the
 * WebDAV share (and in `LocalDirectoryTarget`) named their namespace segment with the raw
 * `/api/me` user id — a bare UUID with no source-tag prefix. After the switch, ABS behaves
 * symmetrically with Komga: `abs_<uuid>` on the wire.
 *
 * Legacy files still exist on every user's share and must remain readable, so both targets run
 * their listing through [migratedName] and MOVE-rename (WebDAV) or dir-rename (local) any hits.
 * The classifier is pure Kotlin so it can be unit-tested at the JVM level without touching a
 * real WebDAV server.
 */
object LegacyAbsNamespaceMigration {

    fun isLegacyAbsFilename(physicalName: String): Boolean {
        val sepIdx = physicalName.indexOf(NAMESPACE_SEPARATOR)
        if (sepIdx <= 0) return false
        val nsSegment = physicalName.substring(0, sepIdx)
        return isLegacyAbsNamespaceSegment(nsSegment)
    }

    fun isLegacyAbsNamespaceSegment(nsSegment: String): Boolean {
        if (nsSegment.startsWith(AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX)) return false
        if (nsSegment.startsWith(KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX)) return false
        return UUID_REGEX.matches(nsSegment)
    }

    fun migratedName(physicalName: String): String {
        if (!isLegacyAbsFilename(physicalName)) return physicalName
        return AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX + physicalName
    }

    private const val NAMESPACE_SEPARATOR = "__"
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
