package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persisted, per-device cover-grid zoom. [scale] is a multiplier applied to the
 * default cover-grid cell sizes: 1.0 keeps the shipped defaults, > 1.0 zooms in
 * (bigger covers, fewer per row), < 1.0 zooms out (smaller covers, more per row).
 *
 * Intentionally global — not scoped by server or library — like the reader
 * formatting / volume-key / wake-lock preferences (ADR 0025): it's an ergonomic
 * UI preference about how dense the browse grids feel, not a property of any
 * library's content.
 */
interface CoverGridDensityStore {
    val scale: Flow<Float>
    suspend fun setScale(value: Float)
}
