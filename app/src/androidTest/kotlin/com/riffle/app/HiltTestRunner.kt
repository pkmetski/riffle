package com.riffle.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun callApplicationOnCreate(app: Application) {
        // HiltTestApplication skips RiffleApplication.onCreate, so the Koin graph that
        // MainActivity's KoinAndroidContext resolves against is never started. Start it here
        // with the production module list, mirroring RiffleApplication. getOrNull() guards
        // against multi-run process reuse where Koin is already up.
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(app)
                modules(riffleKoinModules())
            }
        }
        super.callApplicationOnCreate(app)
    }
}
