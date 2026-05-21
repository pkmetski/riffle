package com.riffle.app.feature.settings

import com.riffle.core.domain.CrashReport
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsViewModelTest {

    private val noOpFormattingStore = object : FormattingPreferencesStore {
        override val preferences = flowOf(FormattingPreferences())
        override suspend fun update(preferences: FormattingPreferences) {}
    }

    private fun makeViewModel(report: CrashReport?) = SettingsViewModel(
        crashReportRepository = object : CrashReportRepository {
            override fun getLastCrashReport() = report
        },
        formattingPreferencesStore = noOpFormattingStore,
    )

    // Cycle 6: exposes last crash timestamp when a report exists
    @Test
    fun `lastCrashReport is populated from repository when a crash has been recorded`() {
        val report = CrashReport(content = "stack trace here", timestampMillis = 1_000_000L)

        val vm = makeViewModel(report)

        assertEquals(report, vm.lastCrashReport)
    }

    // Cycle 7: exposes null when no crash has been recorded
    @Test
    fun `lastCrashReport is null when repository has no report`() {
        val vm = makeViewModel(null)

        assertNull(vm.lastCrashReport)
    }
}
