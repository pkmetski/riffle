package com.riffle.app.sync

import android.content.Context
import com.riffle.core.domain.AnnotationSweepEnqueuer

/**
 * Concrete [AnnotationSweepEnqueuer]: the live controller calls into this on push failure to ask
 * WorkManager to retry the sweep when connectivity returns. The unique-work KEEP semantics in
 * [AnnotationSyncScheduler.sweepNow] make this safe to call repeatedly — duplicates collapse.
 */
class AnnotationSweepEnqueuerImpl(
    private val context: Context,
) : AnnotationSweepEnqueuer {
    override fun enqueue() {
        AnnotationSyncScheduler.sweepNow(context)
    }
}
