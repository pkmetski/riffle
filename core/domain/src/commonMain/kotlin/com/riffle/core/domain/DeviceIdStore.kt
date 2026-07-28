package com.riffle.core.domain

/**
 * A stable per-install identifier, minted once on first run and reused across launches.
 *
 * Per ADR 0025 the `deviceId` only *names* an annotation sync file (`annotations-<deviceId>.jsonld`);
 * annotation identity is the record UUID. v1 mints and persists it but no sync code reads it yet —
 * it exists so the future per-device-file model works without a migration.
 */
interface DeviceIdStore {
    /** Returns the persisted deviceId, generating and storing one on first call. */
    suspend fun getOrCreate(): String
}
