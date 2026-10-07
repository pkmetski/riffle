package com.riffle.app.feature.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Asserts that every string key present in `values/strings.xml` of each feature module that uses
 * composeResources is also present in `values-es/strings.xml` and `values-bg/strings.xml`.
 *
 * Missing a key in a translation file causes a crash on the translated locale in CMP when
 * `stringResource(Res.string.some_key)` can't find the key. This test catches the gap at
 * build time — before it reaches a device.
 *
 * Parameterized over the three modules that gained composeResources as part of #1151: reader-ui,
 * library-ui, and player-ui.
 */
@RunWith(Parameterized::class)
class ComposeResourceStringParityTest(private val modulePath: String) {

    @Test
    fun `all base keys exist in es translation`() =
        assertLocaleCoversBase(modulePath, "values-es")

    @Test
    fun `all base keys exist in bg translation`() =
        assertLocaleCoversBase(modulePath, "values-bg")

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modules() = listOf(
            arrayOf("feature/reader-ui"),
            arrayOf("feature/library-ui"),
            arrayOf("feature/player-ui"),
        )

        // matches only translatable entries (skips translatable="false")
        private val translatablePattern = Regex("""<string name="([^"]+)"(?![^>]*translatable="false")""")
        private val keyPattern = Regex("""<string name="([^"]+)"""")

        private fun assertLocaleCoversBase(modulePath: String, locale: String) {
            val root = findRepoRoot()
            val baseFile = File(root, "$modulePath/src/commonMain/composeResources/values/strings.xml")
            val localeFile = File(root, "$modulePath/src/commonMain/composeResources/$locale/strings.xml")
            assertTrue("Base strings.xml not found: ${baseFile.path}", baseFile.isFile)
            assertTrue("Locale strings.xml not found: ${localeFile.path}", localeFile.isFile)

            val baseKeys = translatablePattern.findAll(baseFile.readText()).map { it.groupValues[1] }.toSet()
            val localeKeys = keyPattern.findAll(localeFile.readText()).map { it.groupValues[1] }.toSet()
            val missing = baseKeys - localeKeys
            assertEquals(
                "$modulePath: keys in values/strings.xml missing from $locale/strings.xml",
                emptySet<String>(),
                missing,
            )
        }

        private fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
                dir = dir.parentFile
            }
            error("Could not locate repo root from ${System.getProperty("user.dir")}")
        }
    }
}
