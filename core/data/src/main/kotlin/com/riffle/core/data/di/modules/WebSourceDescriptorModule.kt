package com.riffle.core.data.di.modules

import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.ChitankaWebSourceDescriptor
import com.riffle.core.domain.GutenbergWebSourceDescriptor
import com.riffle.core.domain.KomgaWebSourceDescriptor
import com.riffle.core.domain.LocalFilesWebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.WebSourceRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hilt façade over [WebSourceDescriptors]. Composables and pure-Kotlin helpers should reference
 * the descriptor `object`s directly; this module exists so Hilt-managed classes (e.g. the future
 * generic singleton-source installer) can inject the [WebSourceRegistry] alongside their other
 * dependencies without depending on a static.
 *
 * Adding a new SourceType requires:
 *   1. adding a `WebSourceDescriptor object` in `:core:domain/WebSourceDescriptor.kt`
 *   2. adding it to [WebSourceDescriptors.all]
 *   3. adding an `@IntoSet` binding here (mechanical)
 * The paired `WebSourceRegistryCompletenessTest` fails if any [com.riffle.core.domain.SourceType]
 * entry is missing a descriptor.
 */
@Module
@InstallIn(SingletonComponent::class)
object WebSourceDescriptorModule {

    @Provides @IntoSet fun provideAbsDescriptor(): WebSourceDescriptor = AbsWebSourceDescriptor

    @Provides @IntoSet fun provideLocalFilesDescriptor(): WebSourceDescriptor =
        LocalFilesWebSourceDescriptor

    @Provides @IntoSet fun provideChitankaDescriptor(): WebSourceDescriptor =
        ChitankaWebSourceDescriptor

    @Provides @IntoSet fun provideGutenbergDescriptor(): WebSourceDescriptor =
        GutenbergWebSourceDescriptor

    @Provides @IntoSet fun provideKomgaDescriptor(): WebSourceDescriptor =
        KomgaWebSourceDescriptor

    @Provides
    @Singleton
    fun provideWebSourceRegistry(
        descriptors: Set<@JvmSuppressWildcards WebSourceDescriptor>,
    ): WebSourceRegistry = WebSourceRegistry(descriptors)
}
