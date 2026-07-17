package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the `checkNoAndroidImports` lint (#550).
 *
 * Guards:
 *  1. Regex correctness — flags `android.*`, `androidx.*`, `java.util.logging`;
 *     does NOT flag `androidx.annotation.*` (pure-JVM, allow-listed by design).
 *  2. Scan-scope correctness — only scans the module roots supplied; missing
 *     modules are silently skipped (future-ready, no-op today); test source sets
 *     are skipped; allowlisted files are skipped.
 */
class AndroidImportLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.root
    }

    private fun writeKt(relative: String, body: String): File {
        val f = root.resolve(relative)
        f.parentFile.mkdirs()
        f.writeText(body)
        return f
    }

    private fun detect(
        moduleRoots: List<String> = listOf("core/models", "core/net", "core/sources"),
        allowlist: Set<String> = emptySet(),
    ) = AndroidImportLint.findAndroidImportOffenders(root, moduleRoots, allowlist)

    // --- Forbidden imports -----------------------------------------------

    @Test
    fun `flags android_content_Context import`() {
        writeKt("core/models/src/main/kotlin/Foo.kt", "import android.content.Context\n")
        assertEquals(1, detect().size)
    }

    @Test
    fun `flags android_util_Log import`() {
        writeKt("core/net/src/main/kotlin/Foo.kt", "import android.util.Log\n")
        assertEquals(1, detect().size)
    }

    @Test
    fun `flags androidx_room import`() {
        writeKt("core/sources/src/main/kotlin/Foo.kt", "import androidx.room.Dao\n")
        assertEquals(1, detect().size)
    }

    @Test
    fun `flags androidx_datastore import`() {
        writeKt("core/models/src/main/kotlin/Foo.kt", "import androidx.datastore.core.DataStore\n")
        assertEquals(1, detect().size)
    }

    @Test
    fun `flags java_util_logging import`() {
        writeKt("core/models/src/main/kotlin/Foo.kt", "import java.util.logging.Logger\n")
        assertEquals(1, detect().size)
    }

    // --- Allowed imports -------------------------------------------------

    @Test
    fun `does not flag androidx_annotation_VisibleForTesting`() {
        writeKt(
            "core/models/src/main/kotlin/Foo.kt",
            "import androidx.annotation.VisibleForTesting\n",
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag androidx_annotation_IntRange`() {
        writeKt(
            "core/models/src/main/kotlin/Foo.kt",
            "import androidx.annotation.IntRange\n",
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag pure-kotlin imports`() {
        writeKt(
            "core/models/src/main/kotlin/Foo.kt",
            """
            import kotlin.collections.List
            import kotlinx.coroutines.flow.Flow
            import kotlinx.serialization.Serializable
            """.trimIndent(),
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag a package named androidx_annotations that is not annotation`() {
        // Guard the negative-lookahead boundary: only `androidx.annotation` (singular) is allowed.
        // Fabricated `androidx.annotationx` or similar should still trip.
        writeKt(
            "core/models/src/main/kotlin/Foo.kt",
            "import androidx.annotations.Something\n",
        )
        assertEquals(1, detect().size)
    }

    // --- Scan-scope ------------------------------------------------------

    @Test
    fun `only scans configured module roots`() {
        // File under an app module — outside the scan set — must be ignored.
        writeKt("app/src/main/kotlin/Foo.kt", "import android.content.Context\n")
        writeKt("core/network/src/main/kotlin/Foo.kt", "import android.content.Context\n")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `missing module directories are skipped silently`() {
        // No core/* directories created at all; detector should return empty, not throw.
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `skips test source sets`() {
        writeKt("core/models/src/test/kotlin/FooTest.kt", "import android.content.Context\n")
        writeKt(
            "core/models/src/androidTest/kotlin/FooAndroidTest.kt",
            "import androidx.room.Dao\n",
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `respects allowlist`() {
        writeKt("core/models/src/main/kotlin/Allowed.kt", "import android.content.Context\n")
        writeKt("core/models/src/main/kotlin/Blocked.kt", "import android.content.Context\n")
        val offenders = detect(allowlist = setOf("core/models/src/main/kotlin/Allowed.kt"))
        assertEquals(1, offenders.size)
        assertEquals("Blocked.kt", offenders.single().file.name)
    }

    @Test
    fun `non-kt files are ignored`() {
        writeKt("core/models/src/main/kotlin/Foo.txt", "import android.content.Context\n")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `import not at start of file still detected`() {
        writeKt(
            "core/models/src/main/kotlin/Foo.kt",
            """
            package com.riffle.core.models

            import kotlinx.serialization.Serializable
            import android.content.Context

            @Serializable
            data class Book(val title: String)
            """.trimIndent(),
        )
        assertEquals(1, detect().size)
    }
}
