package com.riffle.core.data

import com.riffle.core.common.randomUuidString
import com.riffle.core.domain.DeviceIdStore
import platform.Foundation.NSUserDefaults

private const val KEY_DEVICE_ID = "riffle_device_id"

class IosDeviceIdStoreImpl : DeviceIdStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun getOrCreate(): String {
        val stored = defaults.stringForKey(KEY_DEVICE_ID)
        if (stored != null) return stored
        val fresh = randomUuidString()
        defaults.setObject(fresh, forKey = KEY_DEVICE_ID)
        return fresh
    }
}
