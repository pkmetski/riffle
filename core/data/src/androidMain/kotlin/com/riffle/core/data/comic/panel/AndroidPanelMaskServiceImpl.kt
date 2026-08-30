package com.riffle.core.data.comic.panel

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.comic.panel.PageImageDecoder
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionConfig
import com.riffle.core.domain.comic.panel.PanelMaskBinarizer
import com.riffle.core.domain.comic.panel.PanelMaskService
import kotlinx.coroutines.withContext

class AndroidPanelMaskServiceImpl constructor(
    private val config: PanelDetectionConfig,
    private val decoder: PageImageDecoder,
    private val dispatchers: DispatcherProvider,
) : PanelMaskService {

    override suspend fun generateMask(
        pageIndex: Int,
        rawImageBytes: ByteArray,
    ): Pair<PanelBinaryMask, ByteArray>? = withContext(dispatchers.io) {
        val decoded = decoder.decode(rawImageBytes) ?: return@withContext null
        val mask = PanelMaskBinarizer(config).binarize(decoded.grid) ?: return@withContext null
        mask to PanelMaskEncoder.encode(mask)
    }
}
