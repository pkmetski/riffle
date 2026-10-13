package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

/**
 * Renders a figure thumbnail from a data URI (e.g. `data:image/png;base64,…`) inside the annotations panel.
 *
 * Mirrors what Android's [SharedAnnotationsPanel] figureContent lambda does with BitmapFactory:
 * decode the base64 payload and render the image at the width of its container.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
@Composable
internal fun IosAnnotationFigure(dataUri: String, modifier: Modifier = Modifier) {
    val image = remember(dataUri) {
        val base64 = dataUri.substringAfter(",", "")
        if (base64.isEmpty()) return@remember null
        val data = NSData.create(base64EncodedString = base64, options = 0u) ?: return@remember null
        UIImage.imageWithData(data)
    }
    if (image != null) {
        UIKitView(
            factory = {
                UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                }
            },
            update = { view -> view.image = image },
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        Box(modifier = modifier.fillMaxWidth())
    }
}
