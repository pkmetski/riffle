package com.riffle.core.data.di

import com.riffle.core.data.localfiles.AndroidCopyInService
import com.riffle.core.data.localfiles.CopyInService
import com.riffle.core.data.localfiles.FolderWalker
import com.riffle.core.data.localfiles.SafFolderWalker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalFilesModule {

    @Binds
    @Singleton
    abstract fun bindFolderWalker(impl: SafFolderWalker): FolderWalker

    @Binds
    @Singleton
    abstract fun bindCopyInService(impl: AndroidCopyInService): CopyInService
}
