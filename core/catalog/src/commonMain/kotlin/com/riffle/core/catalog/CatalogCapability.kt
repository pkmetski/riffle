package com.riffle.core.catalog

/**
 * Marker for opt-in Catalog behaviours. A concrete [Catalog] declares support by also implementing
 * one or more of these interfaces; UI + repository code gates surfaces on the mixins present via
 * [Catalog.has].
 */
sealed interface CatalogCapability
