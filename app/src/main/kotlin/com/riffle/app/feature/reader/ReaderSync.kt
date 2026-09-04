package com.riffle.app.feature.reader

// ReaderSync types have moved to feature:reader (jvmMain for concrete classes, commonMain for interfaces).
// These typealiases keep existing app imports working unchanged.
typealias CatalogEbookEndpoint = com.riffle.feature.reader.CatalogEbookEndpoint
typealias CatalogAudioEndpoint = com.riffle.feature.reader.CatalogAudioEndpoint
typealias ReaderSyncCycleResult = com.riffle.feature.reader.ReaderSyncCycleResult
typealias AudioLedCycleResult = com.riffle.feature.reader.AudioLedCycleResult
// Concrete classes (jvmMain)
typealias ReaderSyncCoordinator = com.riffle.feature.reader.ReaderSyncCoordinator
typealias AudiobookFollow = com.riffle.feature.reader.AudiobookFollow
// Interfaces (commonMain)
typealias ReaderSyncCoordinatorInterface = com.riffle.feature.reader.ReaderSyncCoordinatorInterface
typealias AudiobookFollowInterface = com.riffle.feature.reader.AudiobookFollowInterface
typealias ReaderSyncFactoryInterface = com.riffle.feature.reader.ReaderSyncFactoryInterface
