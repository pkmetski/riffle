package com.riffle.core.data.di.modules

import com.riffle.core.data.credentialed.AbsCredentialedAuthenticator
import com.riffle.core.data.credentialed.CredentialedAuthenticator
import com.riffle.core.data.credentialed.KomgaCredentialedAuthenticator
import com.riffle.core.domain.SourceType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * Wires the `Map<SourceType, CredentialedAuthenticator>` consumed by `SourceRepositoryImpl`.
 * A new credentialed source (Komga, Calibre-Web, …) adds itself by contributing one `@IntoMap`
 * binding here, mirroring [CatalogModule]'s per-source factory map.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialedAuthenticatorModule {

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.ABS)
    abstract fun bindAbsCredentialedAuthenticator(
        impl: AbsCredentialedAuthenticator,
    ): CredentialedAuthenticator

    @Binds
    @IntoMap
    @SourceTypeKey(SourceType.KOMGA)
    abstract fun bindKomgaCredentialedAuthenticator(
        impl: KomgaCredentialedAuthenticator,
    ): CredentialedAuthenticator
}
