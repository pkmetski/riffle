package com.riffle.core.data

import com.riffle.core.domain.DeviceLabelResolver
import platform.UIKit.UIDevice

class IosDeviceLabelResolver : DeviceLabelResolver {
    override suspend fun resolveLabel(deviceId: String): String = deviceModel()

    override fun deviceModel(): String = UIDevice.currentDevice.name.takeIf { it.isNotBlank() } ?: "iOS Device"
}
