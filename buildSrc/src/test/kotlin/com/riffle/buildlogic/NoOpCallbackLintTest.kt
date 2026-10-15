package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoOpCallbackLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.root
    }

    private fun writeHostFile(relativePath: String, body: String): File {
        val f = root.resolve(relativePath)
        f.parentFile.mkdirs()
        f.writeText(body)
        return f
    }

    private fun detect(vararg hostFiles: String) =
        NoOpCallbackLint.findOffenders(root, hostFiles.toSet())

    @Test
    fun flagsLiteralUnitNoOpAsArgument() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            LibraryItemsScreen(
                onShowAllAnnotations = {},
            )
        """.trimIndent())
        val offenders = detect(path)
        assertEquals(1, offenders.size)
    }

    @Test
    fun flagsTwoArgNoOpAsArgument() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            ReaderHost(
                onPlaylistAdvance = { _, _ -> },
            )
        """.trimIndent())
        val offenders = detect(path)
        assertEquals(1, offenders.size)
    }

    @Test
    fun allowsNamedReference() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            ReaderHost(
                onPlaylistAdvance = NoOp.playlistAdvanceUnavailable,
            )
        """.trimIndent())
        val offenders = detect(path)
        assertTrue(offenders.isEmpty())
    }

    @Test
    fun allowsValDeclarationWithLambdaBody() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            private val noOpAdvance: (String, String) -> Unit = { _, _ -> }
        """.trimIndent())
        val offenders = detect(path)
        assertTrue(offenders.isEmpty())
    }

    @Test
    fun skipsCommentLines() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            // onPlaylistAdvance = { _, _ -> },
        """.trimIndent())
        val offenders = detect(path)
        assertTrue(offenders.isEmpty())
    }

    @Test
    fun skipsNonHostFiles() {
        val path = "feature/library-ui/src/commonMain/SomeScreen.kt"
        writeHostFile(path, """
            onItemSelected = {},
        """.trimIndent())
        // not in hostFiles set
        val offenders = detect("shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt")
        assertTrue(offenders.isEmpty())
    }

    @Test
    fun flagsThreeArgNoOp() {
        val path = "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt"
        writeHostFile(path, """
            LibraryItemDetailScreen(
                onFacetSelected = { _, _, _ -> },
            )
        """.trimIndent())
        val offenders = detect(path)
        assertEquals(1, offenders.size)
    }
}
