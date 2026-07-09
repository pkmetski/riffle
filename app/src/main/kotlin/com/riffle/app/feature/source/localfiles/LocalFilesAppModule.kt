package com.riffle.app.feature.source.localfiles

import com.riffle.core.domain.PdfMetadataExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalFilesAppModule {

    @Binds
    @Singleton
    abstract fun bindPdfMetadataExtractor(impl: PdfiumPdfMetadataExtractor): PdfMetadataExtractor
}
