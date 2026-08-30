package com.riffle.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.riffle.app.di.testAppUpdateKoinModule
import com.riffle.app.di.testDatabaseKoinModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, Application::class.java.name, context)

    override fun callApplicationOnCreate(app: Application) {
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                allowOverride(true)
                androidContext(app)
                modules(riffleKoinModules())
                modules(testDatabaseKoinModule, testAppUpdateKoinModule)
            }
        }
        super.callApplicationOnCreate(app)
    }
}
