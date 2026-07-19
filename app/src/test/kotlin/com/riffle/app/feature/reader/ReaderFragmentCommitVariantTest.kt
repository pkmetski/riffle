package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Regression guard for the Android 15 crash where the reader's Compose `AndroidView`
 * committed a `FragmentTransaction` after the host activity's `onSaveInstanceState`,
 * producing `IllegalStateException: Can not perform this action after
 * onSaveInstanceState`. Every fragment commit inside `EpubReaderScreen` /
 * `PdfReaderScreen` must use `commitNowAllowingStateLoss()`; a plain `commitNow()`
 * runs `checkStateLoss` and crashes.
 */
class ReaderFragmentCommitVariantTest {

    @Test
    fun `epub reader screen has no plain commitNow calls`() {
        assertNoPlainCommitNow(
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
        )
    }

    @Test
    fun `pdf reader screen has no plain commitNow calls`() {
        assertNoPlainCommitNow(
            "app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt",
        )
    }

    private fun assertNoPlainCommitNow(relativePath: String) {
        val source = locate(relativePath).readText()
        val plainCommitNow = Regex("""\.commitNow\(\)""").findAll(source).count()
        assertEquals(
            "$relativePath must use commitNowAllowingStateLoss() for every fragment " +
                "transaction; plain commitNow() crashes after onSaveInstanceState.",
            0,
            plainCommitNow,
        )
    }

    private fun locate(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not locate $relativePath from ${File(".").absolutePath}")
    }
}
