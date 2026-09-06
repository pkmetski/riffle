package com.riffle.core.domain

import com.riffle.core.models.LibraryItem
import java.io.File

/** JVM extension of [CbzRepository] adding [File]-returning methods for Android/JVM hosts. */
interface JvmCbzRepository : CbzRepository {
    suspend fun awaitCachedFile(item: LibraryItem): File?
}
