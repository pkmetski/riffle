package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao

// iOS alias — delegates all logic to the platform-agnostic DaoBackedAudiobookPositionStore.
internal class IosAudiobookPositionStoreImpl(dao: AudiobookPositionDao, clock: Clock) :
    DaoBackedAudiobookPositionStore(dao, clock)
