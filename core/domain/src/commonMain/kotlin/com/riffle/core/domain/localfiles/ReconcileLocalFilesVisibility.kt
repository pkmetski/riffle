package com.riffle.core.domain.localfiles

import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import kotlinx.coroutines.flow.first

suspend fun reconcileLocalFilesVisibility(
    localSource: Source?,
    visibilityStore: LibraryVisibilityPreferencesStore,
) {
    if (localSource?.type != SourceType.LOCAL_FILES) return
    visibilityStore.hiddenLibraryIds(localSource.id).first().forEach { libraryId ->
        visibilityStore.showLibrary(localSource.id, libraryId)
    }
}
