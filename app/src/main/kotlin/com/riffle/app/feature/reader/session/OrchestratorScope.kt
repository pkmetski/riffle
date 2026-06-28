package com.riffle.app.feature.reader.session

import kotlinx.coroutines.CoroutineScope

/**
 * Marker typealias making it explicit at call sites that an orchestrator does NOT capture
 * viewModelScope itself — the VM injects its own scope so teardown is deterministic.
 */
internal typealias OrchestratorScope = CoroutineScope
