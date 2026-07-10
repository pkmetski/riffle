package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the `checkNoServerReferences` lint (#443).
 *
 * Guards:
 *  1. Regex correctness — `\bServer[A-Z]` matches Server-typed identifiers but
 *     not `AbsServer` (no boundary) or `Servers` (no [A-Z]); `\bserverId\b` matches
 *     the bare literal but not `serverIdent` or `myserverId`.
 *  2. Scan-scope correctness — test source sets are skipped; allowlisted files
 *     are skipped; comment-only lines are skipped.
 */
class ServerReferenceLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var scanRoots: List<File>

    @Before
    fun setUp() {
        root = tmp.root
        scanRoots = listOf(root.resolve("app/src"), root.resolve("core")).onEach { it.mkdirs() }
    }

    private fun writeKt(relative: String, body: String): File {
        val f = root.resolve(relative)
        f.parentFile.mkdirs()
        f.writeText(body)
        return f
    }

    private fun detect(allowlist: Set<String> = emptySet()) =
        ServerReferenceLint.findServerReferenceOffenders(scanRoots, root, allowlist)

    // --- Server[A-Z] regex ------------------------------------------------

    @Test
    fun `flags ServerType identifier`() {
        writeKt("app/src/main/kotlin/Foo.kt", """val t: ServerType = ServerType.AUDIOBOOKSHELF""")
        val offenders = detect()
        // One offender per (line, kind) — a line with multiple identifier hits
        // still surfaces as a single SERVER_IDENT record.
        assertEquals(1, offenders.size)
        assertEquals(ServerReferenceLint.Offender.Kind.SERVER_IDENT, offenders.single().kind)
    }

    @Test
    fun `flags ServerRepository identifier`() {
        writeKt("app/src/main/kotlin/Foo.kt", """class ServerRepository""")
        assertEquals(1, detect().size)
    }

    @Test
    fun `does not flag AbsServer — no word boundary before Server`() {
        writeKt("app/src/main/kotlin/Foo.kt", """class AbsServerClient""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag Servers — trailing s, no uppercase after Server`() {
        writeKt("app/src/main/kotlin/Foo.kt", """val servers = listOf<Servers>()""")
        // "servers" lowercase doesn't hit \bServer[A-Z]; "Servers" is Server + s (lowercase).
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag lowercase server`() {
        writeKt("app/src/main/kotlin/Foo.kt", """fun connect(server: String) {}""")
        assertTrue(detect().isEmpty())
    }

    // --- serverId regex ---------------------------------------------------

    @Test
    fun `flags bare serverId identifier`() {
        writeKt("app/src/main/kotlin/Foo.kt", """fun f(serverId: String) {}""")
        val offenders = detect()
        assertEquals(1, offenders.size)
        assertEquals(ServerReferenceLint.Offender.Kind.SERVER_ID, offenders.single().kind)
    }

    @Test
    fun `does not flag serverIdent`() {
        writeKt("app/src/main/kotlin/Foo.kt", """val serverIdent = 1""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not flag myserverId — no word boundary before`() {
        writeKt("app/src/main/kotlin/Foo.kt", """val myserverId = 1""")
        assertTrue(detect().isEmpty())
    }

    // --- Scan-scope + allowlist ------------------------------------------

    @Test
    fun `skips comment-only lines`() {
        writeKt(
            "app/src/main/kotlin/Foo.kt",
            """
            // ServerType used to live here
             * Historical ServerRepository reference
            /* serverId was the old column name */
            """.trimIndent(),
        )
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `skips test source sets`() {
        writeKt("app/src/test/kotlin/FooTest.kt", """val t: ServerType? = null""")
        writeKt("app/src/androidTest/kotlin/FooAndroidTest.kt", """val id: String = serverId""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `respects allowlist`() {
        writeKt("core/domain/src/main/kotlin/ServerType.kt", """enum class ServerType { A }""")
        writeKt("core/other/src/main/kotlin/Foo.kt", """val t: ServerType? = null""")
        val allow = setOf("core/domain/src/main/kotlin/ServerType.kt")
        val offenders = ServerReferenceLint.findServerReferenceOffenders(scanRoots, root, allow)
        assertEquals(1, offenders.size)
        assertEquals("Foo.kt", offenders.single().file.name)
    }

    @Test
    fun `non-kt files are ignored`() {
        writeKt("app/src/main/kotlin/Foo.txt", """ServerType""")
        assertTrue(detect().isEmpty())
    }

    @Test
    fun `flags both kinds on the same line`() {
        writeKt("app/src/main/kotlin/Foo.kt", """fun f(serverId: String, type: ServerType) {}""")
        val offenders = detect()
        assertEquals(2, offenders.size)
        val kinds = offenders.map { it.kind }.toSet()
        assertTrue(ServerReferenceLint.Offender.Kind.SERVER_IDENT in kinds)
        assertTrue(ServerReferenceLint.Offender.Kind.SERVER_ID in kinds)
    }
}
