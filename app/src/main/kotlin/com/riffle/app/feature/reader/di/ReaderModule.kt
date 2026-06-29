package com.riffle.app.feature.reader.di

import com.riffle.app.feature.reader.readaloud.PlayerController
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class ReaderModule {
    @Binds
    abstract fun bindPlayerController(impl: PlayerCoordinator): PlayerController
}
