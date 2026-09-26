package com.riffle.core.data.di

/**
 * Koin qualifier for the `Map<SourceType, RemoteUserIdResolver>` both platforms bind for
 * [com.riffle.core.data.sources.SyncNamespaceResolver]. Qualified because Koin keys generic
 * bindings by their erased class — an unqualified `Map` binding would silently replace the
 * `Map<SourceType, SourceAdapter>` / `Map<SourceType, CatalogFactory>` bindings in the same graph.
 */
const val REMOTE_USER_ID_RESOLVERS_BY_SOURCE_TYPE = "remoteUserIdResolversBySourceType"
