package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the `checkRiffleLogTags` lint (#337, #347).
 *
 * Guards two surfaces the gradle task depends on:
 *  1. Regex correctness — matches `Log.[dweiv]("RIFFLE_…"` and excludes RIFFLE_TEST.
 *  2. Scan-scope correctness — `core/logging/src/main` is excluded; everything else
 *     under `app/src` and `core/` is scanned.
 *
 * The tests build a fixture project on disk and call the pure detector directly —
 * no GradleRunner, no live tree.
 */
class RiffleLogTagLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var allowedRoot: File
    private lateinit var scanRoots: List<File>

    @org.junit.Before
    fun setUp() {
        root = tmp.root
        allowedRoot = root.resolve("core/logging/src/main").also { it.mkdirs() }
        scanRoots = listOf(root.resolve("app/src"), root.resolve("core")).onEach { it.mkdirs() }
    }

    private fun writeKt(relative: String, body: String): File {
        val f = root.resolve(relative)
        f.parentFile.mkdirs()
        f.writeText(body)
        return f
    }

    private fun detect() =
        RiffleLogTagLint.findRiffleLogTagOffenders(scanRoots, allowedRoot)

    // --- regex correctness -------------------------------------------------

    @Test
    fun `flags forbidden literal outside core_logging`() {
        writeKt("app/src/main/kotlin/Foo.kt", """Log.d("RIFFLE_RA", "boom")""")
        val offenders = detect()
        assertEquals(1, offenders.size)
        assertEquals("Foo.kt", offenders.single().file.name)
    }

    @Test
    fun `matches each of d w e i v`() {
        listOf("d", "w", "e", "i", "v").forEachIndexed { i, level ->
            writeKt("app/src/main/kotlin/F$i.kt", """Log.$level("RIFFLE_AB", "x")""")
        }
        assertEquals(5, detect().size)
    }

    @Test
    fun `does not flag RIFFLE_TEST`() {
        writeKt("app/src/main/kotlin/Foo.kt", """Log.d("RIFFLE_TEST", "ok")""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag unrelated tags`() {
        writeKt("app/src/main/kotlin/Foo.kt", """Log.d("SomeOtherTag", "ok")""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag other Log methods like println or wtf`() {
        // wtf is intentionally not in [dweiv]; if the regex is loosened to allow it, this fails.
        writeKt("app/src/main/kotlin/Foo.kt", """Log.wtf("RIFFLE_RA", "x")""")
        writeKt("app/src/main/kotlin/Bar.kt", """println("RIFFLE_RA: x")""")
        assertTrue(detect().isEmpty())
    }

    // --- scan-scope correctness --------------------------------------------

    @Test
    fun `excludes core_logging src main`() {
        writeKt(
            "core/logging/src/main/kotlin/LogChannel.kt",
            """Log.d("RIFFLE_RA", "allowed here")""",
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `scans core_logging src test (only main is exempt)`() {
        // Only `core/logging/src/main` is exempt — tests in core/logging are still scanned.
        writeKt(
            "core/logging/src/test/kotlin/LogChannelTest.kt",
            """Log.d("RIFFLE_RA", "should fail")""",
        )
        assertEquals(1, detect().size)
    }

    @Test
    fun `scans app_src and other core modules`() {
        writeKt("app/src/main/kotlin/Foo.kt", """Log.d("RIFFLE_RA", "x")""")
        writeKt("core/data/src/main/kotlin/Bar.kt", """Log.e("RIFFLE_AB", "x")""")
        assertEquals(2, detect().size)
    }

    @Test
    fun `non-kt files are ignored`() {
        writeKt("app/src/main/kotlin/Foo.txt", """Log.d("RIFFLE_RA", "x")""")
        assertTrue(detect().isEmpty())
    }

    // --- guards against rule-loosening regressions -------------------------

    @Test
    fun `regex would fail if only d is allowed`() {
        // Sanity: ensures the [dweiv] character class actually does work — if anyone
        // accidentally narrows it to [d], the `matches each of d w e i v` test above
        // catches it. This test documents that the FORBIDDEN_PATTERN as published
        // must keep all five levels.
        val pattern = RiffleLogTagLint.FORBIDDEN_PATTERN.pattern
        listOf("d", "w", "e", "i", "v").forEach { level ->
            assertTrue("regex must accept Log.$level", pattern.contains("[dweiv]"))
        }
    }
}
