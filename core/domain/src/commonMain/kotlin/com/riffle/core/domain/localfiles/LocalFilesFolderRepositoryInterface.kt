package com.riffle.core.domain.localfiles

interface LocalFilesFolderRepositoryInterface {
    suspend fun removeFolder(sourceId: String, treeUri: String)
}
