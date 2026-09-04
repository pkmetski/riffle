package com.riffle.core.domain

/** A locally-stored item, identified by its owning Source and item id (ADR 0031). */
data class StoredItemRef(val sourceId: String, val itemId: String)
