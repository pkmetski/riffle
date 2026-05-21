package com.riffle.app.feature.settings

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val crashReportRepository: CrashReportRepository,
) : ViewModel() {

    val lastCrashReport: CrashReport? = crashReportRepository.getLastCrashReport()
}
