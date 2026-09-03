package com.riffle.feature.source

import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceTypePickerViewModelTest {

    @Test
    fun `initial value hides all singleton types before first emission`() {
        val vm = SourceTypePickerViewModel(FakeSourceRepository(emptyList()))
        assertEquals(SourceType.values().toSet(), vm.installedTypes.value)
    }

    @Test
    fun `installed types reflects single source from repository`() = runTest {
        val sources = listOf(fakeSource(SourceType.ABS))
        val repo = FakeSourceRepository(sources)
        val vm = SourceTypePickerViewModel(repo)
        repo.emit(sources)
        val types = vm.installedTypes.first { it != SourceType.values().toSet() }
        assertEquals(setOf(SourceType.ABS), types)
    }

    @Test
    fun `empty repository emits empty set after first emission`() = runTest {
        val repo = FakeSourceRepository(emptyList())
        val vm = SourceTypePickerViewModel(repo)
        repo.emit(emptyList())
        val types = vm.installedTypes.first { it != SourceType.values().toSet() }
        assertTrue(types.isEmpty())
    }

    @Test
    fun `multiple sources produce set of their types`() = runTest {
        val sources = listOf(fakeSource(SourceType.ABS), fakeSource(SourceType.CHITANKA))
        val repo = FakeSourceRepository(sources)
        val vm = SourceTypePickerViewModel(repo)
        repo.emit(sources)
        val types = vm.installedTypes.first { it != SourceType.values().toSet() }
        assertEquals(setOf(SourceType.ABS, SourceType.CHITANKA), types)
    }
}

class SourceSetupViewModelTest {

    @Test
    fun `pendingServer is initially null`() {
        val vm = SourceSetupViewModel()
        assertEquals(null, vm.pendingServer)
    }

    @Test
    fun `pendingServer can be set and retrieved`() {
        val vm = SourceSetupViewModel()
        val pending = fakePendingSource()
        vm.pendingServer = pending
        assertEquals(pending, vm.pendingServer)
    }

    @Test
    fun `pendingServer can be cleared`() {
        val vm = SourceSetupViewModel()
        vm.pendingServer = fakePendingSource()
        vm.pendingServer = null
        assertEquals(null, vm.pendingServer)
    }
}

private fun fakeSource(type: SourceType) = Source(
    id = "id-${type.name}",
    url = SourceUrl.parse("https://example.com") ?: error("invalid test URL"),
    isActive = false,
    insecureConnectionAllowed = false,
    username = "",
    type = type,
)

private fun fakePendingSource() = PendingSource(
    url = SourceUrl.parse("https://abs.example.com") ?: error("invalid test URL"),
    username = "user",
    userId = "user-1",
    token = "tok",
    password = "pass",
    insecureConnectionAllowed = false,
    libraries = emptyList(),
)

private class FakeSourceRepository(
    initial: List<Source>,
) : SourceRepository {
    private val _flow = MutableStateFlow(initial)

    fun emit(sources: List<Source>) { _flow.value = sources }

    override fun observeAll(): Flow<List<Source>> = _flow
    override suspend fun getActive(): Source? = _flow.value.firstOrNull { it.isActive }
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException("not used in fake"))
    override suspend fun setActive(sourceId: String) = Unit
    override suspend fun remove(sourceId: String) = Unit
    override suspend fun getSourceVersion(sourceId: String): String? = null
}
