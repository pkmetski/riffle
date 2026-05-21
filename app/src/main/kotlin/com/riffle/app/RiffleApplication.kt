package com.riffle.app

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import org.acra.ReportField
import org.acra.ktx.initAcra

@HiltAndroidApp
class RiffleApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra {
            reportContent = listOf(
                ReportField.STACK_TRACE,
                ReportField.PHONE_MODEL,
                ReportField.ANDROID_VERSION,
                ReportField.APP_VERSION_NAME,
                ReportField.AVAILABLE_MEM_SIZE,
            )
        }
    }
}
