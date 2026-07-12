package com.riffle.core.data.di.modules

import com.riffle.core.data.sync.AbsRemoteUserIdResolver
import com.riffle.core.data.sync.KomgaRemoteUserIdResolver
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.SourceType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * Wires the `Map<SourceType, RemoteUserIdResolver>` consumed by `SourceRepositoryImpl` (#529).
 * A new server-backed source contributes one `@IntoMap` binding here to become sync-eligible;
 * anonymous / local sources omit a binding (their descriptor returns
 * [com.riffle.core.domain.SyncNamespace.LocalOnly] so the repository never asks).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteUserIdResolverModule {

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.ABS)
    abstract fun bindAbsResolver(impl: AbsRemoteUserIdResolver): RemoteUserIdResolver

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.KOMGA)
    abstract fun bindKomgaResolver(impl: KomgaRemoteUserIdResolver): RemoteUserIdResolver
}
