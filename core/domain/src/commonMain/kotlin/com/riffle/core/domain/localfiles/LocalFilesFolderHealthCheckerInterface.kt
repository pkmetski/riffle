package com.riffle.core.domain.localfiles

interface LocalFilesFolderHealthCheckerInterface {
    fun healthFor(treeUris: Collection<String>): Map<String, Boolean>
}
