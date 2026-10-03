package com.riffle.app.feature.reader.readaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the missing-Readaloud-highlight bugs.
 *
 * Bug 1 (fix 8d06cc8): [PlayerCoordinator] was injected as a factory — each injection site
 * (ReadaloudSession and EpubReaderViewModel) received a separate instance. The session drove
 * audio on one [AudioClockTicker] while the ViewModel's `activeFragmentRef` observed a
 * different one that never received audio-clock updates, so the highlight stayed on `null`.
 * Fixed by registering with `single`.
 *
 * Bug 2: After changing the readaloud colour in Settings and reopening the reader, the
 * highlight would permanently stop appearing until the app was restarted. Root cause: the
 * [PlayerCoordinator] is app-lifetime (Koin `single`), but [EpubReaderViewModel.onCleared]
 * called [PlayerCoordinator.dispose] which permanently cancelled the coordinator's internal
 * [kotlinx.coroutines.CoroutineScope]. [AudioClockTicker]'s state-collection coroutine runs
 * in that scope, so once cancelled it could never update `activeFragmentRef` again — all
 * subsequent reader sessions would see `null` for the fragment ref and no highlight. Fixed
 * by removing the [PlayerCoordinator.dispose] call from [EpubReaderViewModel.onCleared].
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

    @Test
    fun `EpubReaderViewModel onCleared does not call playerCoordinator dispose`() {
        val vmFile = File("src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt")
        assertTrue(
            "EpubReaderViewModel.kt must exist at ${vmFile.absolutePath}",
            vmFile.exists(),
        )
        val text = vmFile.readText()
        // The onCleared body must not call playerCoordinator.dispose(). PlayerCoordinator is a
        // Koin singleton whose internal scope must live for the app's lifetime — cancelling it
        // permanently kills AudioClockTicker's state-collection coroutine so every subsequent
        // readaloud session sees activeFragmentRef=null and shows no highlight.
        // We match only un-commented lines — a `// NOTE: do NOT call playerCoordinator.dispose()`
        // comment is fine; an actual invocation is not.
        val callsDispose = text.lines()
            .dropWhile { !it.contains("override fun onCleared()") }
            .drop(1) // skip the `override fun onCleared()` line itself
            .takeWhile { it != "    }" && it != "    })" } // stop at closing brace of onCleared
            .filter { !it.trimStart().startsWith("//") } // skip comment lines
            .any { it.contains("playerCoordinator.dispose()") }
        assertFalse(
            "EpubReaderViewModel.onCleared() must not call playerCoordinator.dispose(). " +
                "PlayerCoordinator is a Koin singleton — calling dispose() permanently cancels " +
                "its AudioClockTicker coroutine, so all subsequent readaloud sessions lose the " +
                "sentence highlight until the app restarts. See PlayerCoordinatorScopeTest.",
            callsDispose,
        )
    }
}
