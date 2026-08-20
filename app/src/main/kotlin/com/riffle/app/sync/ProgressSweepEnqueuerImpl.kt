package com.riffle.app.sync

import android.content.Context
import com.riffle.core.domain.ProgressSweepEnqueuer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressSweepEnqueuerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProgressSweepEnqueuer {
    override fun enqueue() {
        ProgressSyncScheduler.sweepNow(context)
    }
}
