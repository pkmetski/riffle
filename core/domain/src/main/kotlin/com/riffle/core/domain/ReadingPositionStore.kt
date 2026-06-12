package com.riffle.core.domain

/** The ebook reading position (an EPUB/PDF locator string), stored per (serverId, itemId). */
interface ReadingPositionStore : PositionStore<String>
