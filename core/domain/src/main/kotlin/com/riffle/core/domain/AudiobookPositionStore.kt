package com.riffle.core.domain

/** The audiobook listen position (book-absolute seconds), stored per (sourceId, itemId). */
interface AudiobookPositionStore : PositionStore<Double>
