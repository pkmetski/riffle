package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.ReadingPositionDao

// iOS alias — delegates all logic to the platform-agnostic DaoBackedReadingPositionStore.
internal class IosReadingPositionStoreImpl(dao: ReadingPositionDao, clock: Clock) :
    DaoBackedReadingPositionStore(dao, clock)
