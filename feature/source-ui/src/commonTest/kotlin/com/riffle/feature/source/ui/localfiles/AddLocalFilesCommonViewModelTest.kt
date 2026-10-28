package com.riffle.feature.source.ui.localfiles

import com.riffle.core.data.localfiles.FolderUri
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AddLocalFilesCommonViewModelTest {

    private val testUri = FolderUri("/test/folder")
    private val testScope = TestScope()

    @Test
    fun initialStateIsIdle() {
        val vm = viewModel(installer = FakeInstaller())
        assertIs<AddLocalFilesCommonViewModel.State.Idle>(vm.state.value)
    }

    @Test
    fun nullUriSetsCancelledState() = testScope.runTest {
        val vm = viewModel(installer = FakeInstaller())
        vm.onFolderPicked(null)
        assertIs<AddLocalFilesCommonViewModel.State.Cancelled>(vm.state.value)
    }

    @Test
    fun successfulInstallSetsSuccessState() = testScope.runTest {
        val installer = FakeInstaller(report = LocalFilesInstallerInterface.InstallReport(added = 3, failures = 1))
        val vm = viewModel(installer = installer)
        vm.onFolderPicked(testUri)
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertIs<AddLocalFilesCommonViewModel.State.Success>(state)
        assertEquals(3, state.added)
        assertEquals(1, state.failures)
    }

    @Test
    fun failingInstallSetsErrorState() = testScope.runTest {
        val installer = FakeInstaller(throws = RuntimeException("disk full"))
        val vm = viewModel(installer = installer)
        vm.onFolderPicked(testUri)
        testScheduler.advanceUntilIdle()
        val state = vm.state.value
        assertIs<AddLocalFilesCommonViewModel.State.Error>(state)
        assertEquals("disk full", state.message)
    }

    private fun viewModel(installer: LocalFilesInstallerInterface) =
        AddLocalFilesCommonViewModel(installer = installer, scope = testScope)
}

private class FakeInstaller(
    private val report: LocalFilesInstallerInterface.InstallReport =
        LocalFilesInstallerInterface.InstallReport(added = 0, failures = 0),
    private val throws: Throwable? = null,
) : LocalFilesInstallerInterface {
    override suspend fun installFolder(folderUri: FolderUri): LocalFilesInstallerInterface.InstallReport {
        if (throws != null) throw throws
        return report
    }
}
