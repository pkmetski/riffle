package com.riffle.core.data.di.modules

import com.riffle.core.models.SourceType
import com.riffle.core.sources.SourceAdapter
import com.riffle.core.sources.abs.AbsSourceAdapter
import com.riffle.core.sources.komga.KomgaSourceAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialedAuthenticatorModule {

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.ABS)
    abstract fun bindAbsSourceAdapter(impl: AbsSourceAdapter): SourceAdapter

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.KOMGA)
    abstract fun bindKomgaSourceAdapter(impl: KomgaSourceAdapter): SourceAdapter
}
