package com.riffle.app.feature.reader.readaloud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for the missing-Readaloud-highlight bug (fix 8d06cc8).
 *
 * [PlayerCoordinator] is injected into both `ReadaloudSession` (as a `PlayerController`)
 * and `EpubReaderViewModel` (as its concrete type). Without a shared-instance binding,
 * each injection point would get a fresh instance — the session would drive audio on one
 * `AudioClockTicker` while the ViewModel's `activeFragmentRef` observed a different one
 * that never sees the audio clock, so the synced sentence highlight would stay on `null`.
 *
 * With Koin, `single { PlayerCoordinator(...) }` ensures the same instance is returned
 * for every `get<PlayerCoordinator>()` call, replacing the former `@ViewModelScoped` Hilt
 * annotation that provided the same guarantee. This test pins that the registration uses
 * `single`, not `factory`, so a careless refactor doesn't silently break the highlight.
 */
class PlayerCoordinatorScopeTest {
    @Test
    fun `AppKoinModules registers PlayerCoordinator as a singleton`() {
        val source = File("src/main/kotlin/com/riffle/app/di/AppKoinModules.kt")
        assertTrue(
            "AppKoinModules.kt must exist at ${source.absolutePath}",
            source.exists(),
        )
        val text = source.readText()
        // Match `single { PlayerCoordinator(` — confirms singleton (not factory) registration.
        // A factory would hand separate instances to ReadaloudSession and EpubReaderViewModel,
        // breaking the Readaloud highlight exactly as described in fix 8d06cc8.
        val singletonPattern = Regex("""single\s*\{\s*PlayerCoordinator\s*\(""")
        assertTrue(
            "AppKoinModules must register PlayerCoordinator with `single { PlayerCoordinator(...)` " +
                "to ensure ReadaloudSession and EpubReaderViewModel share the same AudioClockTicker. " +
                "A `factory` registration would silently break the Readaloud highlight. See fix 8d06cc8.",
            singletonPattern.containsMatchIn(text),
        )
    }
}
