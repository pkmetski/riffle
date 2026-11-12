package com.riffle.core.domain

import com.riffle.core.domain.usecase.ReadaloudReviewActions
import org.koin.dsl.module

val coreDomainKoinModule = module {
    single<TimeProvider> { SystemTimeProvider }
    single { ReadaloudReviewActions(mutator = get(), linkRepository = get(), audioIdentityResolver = get(), audioPlaybackPreferencesStore = get()) }
}
