package com.riffle.core.data.localfiles

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType

class LocalFilesCatalogFactory(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val itemDao: LibraryItemDao,
    private val overrideDao: LocalFileMetadataOverrideDao,
    private val fileReader: LocalFilesFileReader,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.LOCAL_FILES

    override suspend fun create(source: Source): Catalog? = LocalFilesCatalog(
        sourceId = source.id,
        folderDao = folderDao,
        fileDao = fileDao,
        fileFolderDao = fileFolderDao,
        itemDao = itemDao,
        overrideDao = overrideDao,
        fileReader = fileReader,
    )
}
