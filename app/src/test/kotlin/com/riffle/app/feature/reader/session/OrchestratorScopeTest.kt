package com.riffle.app.feature.reader.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class OrchestratorScopeTest {
    @Test fun `is a typealias for CoroutineScope`() {
        // Verify the typealias allows assignment from CoroutineScope
        val orchestratorScope: OrchestratorScope = CoroutineScope(Dispatchers.Unconfined)
        // And verify an OrchestratorScope can be used where CoroutineScope is expected
        val asCoroutineScope: CoroutineScope = orchestratorScope
        // Verify we can call CoroutineScope methods on it
        orchestratorScope.coroutineContext
    }
}
