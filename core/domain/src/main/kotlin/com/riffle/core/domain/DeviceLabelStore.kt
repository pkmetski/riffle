package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persists this device's user-overridden display label.
 *
 * A non-null value means the user typed a name on the Maintenance screen and we should
 * publish it verbatim in the annotation-file header (see [AnnotationFileHeader]). A null value means fall back
 * through the [DeviceLabelResolver] chain.
 */
interface DeviceLabelStore {
    /** Latest persisted override; null when the user has never set one (or cleared it). */
    fun observe(): Flow<String?>

    /** Snapshot read used by sync-time publish. */
    suspend fun get(): String?

    /** Saves a user override. Pass `null` to clear and fall back to the resolver chain. */
    suspend fun set(label: String?)
}
