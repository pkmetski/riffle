package com.riffle.shared.reader

import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.network.KomgaCbzApi
import kotlinx.coroutines.runBlocking

class IosKomgaCbzImageSource(
    private val api: KomgaCbzApi,
    private val baseUrl: String,
    private val bookId: String,
    private val token: String,
    private val insecureAllowed: Boolean,
    override val pageCount: Int,
) : ComicImageSource {

    override fun imageBytes(pageIndex: Int): ByteArray = runBlocking {
        api.fetchCbzPage(
            baseUrl = baseUrl,
            bookId = bookId,
            pageIndex = pageIndex,
            maxWidth = null,
            token = token,
            insecureAllowed = insecureAllowed,
        )
    }

    override fun mediaType(pageIndex: Int): String = "image/jpeg"
}
