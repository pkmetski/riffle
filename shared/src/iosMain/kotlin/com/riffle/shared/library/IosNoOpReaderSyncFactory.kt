package com.riffle.shared.library

import com.riffle.feature.reader.AudiobookFollowInterface
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import com.riffle.feature.reader.ReaderSyncFactoryInterface

internal object IosNoOpReaderSyncFactory : ReaderSyncFactoryInterface {
    override suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinatorInterface? = null
    override suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollowInterface? = null
}
