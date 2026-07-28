package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OkHttpConfinementLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.root
    }

    private fun writeKt(relative: String, body: String): File {
        val file = root.resolve(relative)
        file.parentFile.mkdirs()
        file.writeText(body)
        return file
    }

    private fun detect(
        allowedRoots: List<String> = OkHttpConfinementLint.ALLOWED_ROOTS,
    ) = OkHttpConfinementLint.findOkHttpOutsideCoreNet(root, allowedRoots)

    @Test
    fun `flags okhttp import outside core net platform source sets`() {
        writeKt("core/data/src/main/kotlin/Foo.kt", "import okhttp3.OkHttpClient\n")

        val offender = detect().single()

        assertEquals(1, offender.lineNumber)
        assertEquals("Foo.kt", offender.file.name)
    }

    @Test
    fun `allows okhttp import in core net jvmMain`() {
        writeKt("core/net/src/jvmMain/kotlin/Foo.kt", "import okhttp3.OkHttpClient\n")

        assertTrue(detect().isEmpty())
    }

    @Test
    fun `allows okhttp import in core net androidMain`() {
        writeKt("core/net/src/androidMain/kotlin/Foo.kt", "import okhttp3.OkHttpClient\n")

        assertTrue(detect().isEmpty())
    }

    @Test
    fun `does not treat an allowed-root name prefix as inside that root`() {
        writeKt("core/net/src/jvmMainLeak/kotlin/Foo.kt", "import okhttp3.OkHttpClient\n")

        assertEquals(1, detect().size)
    }

    @Test
    fun `skips every kotlin multiplatform test source set`() {
        writeKt("core/data/src/test/kotlin/UnitTest.kt", "import okhttp3.OkHttpClient\n")
        writeKt("core/data/src/jvmTest/kotlin/JvmTest.kt", "import okhttp3.OkHttpClient\n")
        writeKt("core/data/src/commonTest/kotlin/CommonTest.kt", "import okhttp3.OkHttpClient\n")
        writeKt("core/data/src/iosTest/kotlin/IosTest.kt", "import okhttp3.OkHttpClient\n")

        assertTrue(detect().isEmpty())
    }

    @Test
    fun `ignores non-import references and non-kotlin files`() {
        writeKt("core/data/src/main/kotlin/Foo.kt", "val packageName = \"okhttp3.OkHttpClient\"\n")
        val text = root.resolve("core/data/src/main/kotlin/Foo.txt")
        text.parentFile.mkdirs()
        text.writeText("import okhttp3.OkHttpClient\n")

        assertTrue(detect().isEmpty())
    }
}
