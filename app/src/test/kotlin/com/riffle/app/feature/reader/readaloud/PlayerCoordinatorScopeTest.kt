package com.riffle.app.feature.reader.readaloud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for the missing-Readaloud-highlight bug (fix 8d06cc8).
 *
 * [PlayerCoordinator] is injected into both `ReadaloudSession` (as a `PlayerController`)
 * and `EpubReaderViewModel` (as its concrete type). Without a scope annotation, Hilt
 * hands each injection point a fresh instance — the session drives audio on one
 * `AudioClockTicker` while the ViewModel's `activeFragmentRef` observes a different one
 * that never sees the audio clock, so the synced sentence highlight stays on `null` and
 * never lights up on screen.
 *
 * Both call sites live in the ViewModel scope, so `@ViewModelScoped` is the right
 * shared lifetime.
 *
 * The check is on the source file rather than reflection because
 * `dagger.hilt.android.scopes.ViewModelScoped` carries `RetentionPolicy.CLASS`, so it's
 * invisible via `Class#isAnnotationPresent`. A source-string check is intentionally
 * simple: it catches an accidental removal of the annotation, which is exactly the
 * regression this test guards against. It does not try to prove that Hilt DI wires up
 * the shared instance correctly at runtime — that requires an instrumented test with a
 * real ViewModelComponent.
 */
class PlayerCoordinatorScopeTest {
    @Test
    fun `PlayerCoordinator source declares @ViewModelScoped on the class`() {
        // Anchor from the JVM test working dir (project root) — the same layout every
        // gradle module uses, so this file's tests always resolve the same source path.
        val source = File("src/main/kotlin/com/riffle/app/feature/reader/readaloud/PlayerCoordinator.kt")
        assertTrue(
            "PlayerCoordinator.kt must exist at ${source.absolutePath}",
            source.exists(),
        )
        val text = source.readText()
        // Match the annotation immediately preceding the class declaration — the only
        // position that binds the scope to the injectable. Whitespace-tolerant so trivial
        // reformatting doesn't fire a false positive.
        val classHeaderPattern = Regex("""@ViewModelScoped\s+class\s+PlayerCoordinator\b""")
        assertTrue(
            "PlayerCoordinator must carry @ViewModelScoped on the class declaration. Without " +
                "it, Hilt provides separate instances to ReadaloudSession and " +
                "EpubReaderViewModel, and the ViewModel's activeFragmentRef never receives " +
                "audio-clock ticks — the Readaloud highlight breaks silently. See fix 8d06cc8.",
            classHeaderPattern.containsMatchIn(text),
        )
    }
}
