package com.riffle.app.dictionary

import androidx.work.ListenableWorker.Result

internal fun downloadResultFor(downloadOk: Boolean): Result =
    if (downloadOk) Result.success() else Result.retry()
