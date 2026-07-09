package com.riffle.core.data.localfiles

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports per-folder SAF-URI health for the LocalFiles Source. A folder is "healthy" when the
 * user's persistable URI grant on its tree URI is still present in [ContentResolver.getPersistedUriPermissions]
 * — the grant survives process death, but the user can revoke it any time in system Settings, and
 * once revoked our next read attempt would throw [SecurityException]. Checking the persisted-grant
 * list catches this state up-front without needing to touch the tree.
 *
 * Already-copied bytes stay readable regardless (they live under app-private storage), so an
 * unhealthy folder degrades to "we can't rescan for new files" — not "your books disappeared".
 */
@Singleton
class LocalFilesFolderHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * True when [treeUri] still has a persistable read grant on this device. False after the
     * user revoked it in system Settings, or if the app never held the grant (rare — added
     * folders always [ContentResolver.takePersistableUriPermission]).
     */
    fun isHealthy(treeUri: String): Boolean {
        val target = try { Uri.parse(treeUri) } catch (_: Throwable) { return false }
        return context.contentResolver.persistedUriPermissions.any { grant ->
            grant.uri == target && grant.isReadPermission
        }
    }

    /** Bulk variant — cheaper than N calls when the caller has a full folder set to report on. */
    fun healthFor(treeUris: Collection<String>): Map<String, Boolean> {
        if (treeUris.isEmpty()) return emptyMap()
        val heldReadable: Set<String> = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(mutableSetOf()) { it.uri.toString() }
        return treeUris.associateWith { it in heldReadable }
    }
}
