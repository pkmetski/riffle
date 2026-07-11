package com.riffle.core.data.localfiles

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import javax.inject.Inject

/**
 * Builds a [LocalFilesCatalog] per LocalFiles Source row. Unlike [AbsCatalogFactory] there is no
 * per-Source auth to resolve — the DAOs are singletons and the Catalog just carries the sourceId
 * so its queries scope correctly.
 */
class LocalFilesCatalogFactory @Inject constructor(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val itemDao: LibraryItemDao,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.LOCAL_FILES

    override suspend fun create(source: Source): Catalog? = LocalFilesCatalog(
        sourceId = source.id,
        folderDao = folderDao,
        fileDao = fileDao,
        fileFolderDao = fileFolderDao,
        itemDao = itemDao,
    )
}
