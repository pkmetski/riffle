package com.riffle.feature.library

interface CoverImageCopier {
    suspend operator fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String?
}
