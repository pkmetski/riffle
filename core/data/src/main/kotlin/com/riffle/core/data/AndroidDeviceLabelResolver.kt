package com.riffle.core.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.DeviceLabelStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Android implementation of [DeviceLabelResolver].
 *
 * Resolution order — first non-blank wins (see [DeviceLabelResolver] kdoc):
 * 1. User override from [DeviceLabelStore].
 * 2. `Settings.Global.DEVICE_NAME` — gated on `SDK_INT >= 25` because Riffle's minSdk is 24.
 * 3. `Build.MANUFACTURER + " " + Build.MODEL` with manufacturer deduplicated when MODEL
 *    already starts with it (case-insensitive). Always non-blank on a real device — Android
 *    guarantees both `MANUFACTURER` and `MODEL` are non-null system constants.
 * 4. `device-${deviceId.take(8)}` — backstop that fires only on the (effectively impossible)
 *    case of all three sources being blank.
 */
class AndroidDeviceLabelResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val labelStore: DeviceLabelStore,
) : DeviceLabelResolver {

    override suspend fun resolveLabel(deviceId: String): String {
        val override = labelStore.get()?.trim().orEmpty()
        if (override.isNotEmpty()) return override

        val osName = osDeviceName()?.trim().orEmpty()
        if (osName.isNotEmpty()) return osName

        val build = deviceModel().trim()
        if (build.isNotEmpty()) return build

        return "device-${deviceId.take(8)}"
    }

    override fun deviceModel(): String = composeBuildLabel(Build.MANUFACTURER, Build.MODEL)

    private fun osDeviceName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        return try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        /** Visible for tests. */
        internal fun composeBuildLabel(manufacturer: String?, model: String?): String {
            val mfg = manufacturer?.trim().orEmpty()
            val mdl = model?.trim().orEmpty()
            if (mdl.isEmpty()) return mfg
            if (mfg.isEmpty()) return mdl
            return if (mdl.startsWith(mfg, ignoreCase = true)) mdl else "$mfg $mdl"
        }
    }
}
