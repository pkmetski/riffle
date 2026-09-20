package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalizationResourceLintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resRoot get() = tmp.root.resolve("app/src/main/res")

    @Test
    fun `complete locale has no offenders`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="app_name" translatable="false">Riffle</string>
                <string name="export_pdf_error">Couldn't generate PDF</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings(
            "values-es",
            """
            <resources>
                <string name="export_pdf_error">No se pudo generar el PDF</string>
            </resources>
            """.trimIndent(),
        )

        assertTrue(LocalizationResourceLint.findLocalizationOffenders(resRoot).isEmpty())
    }

    @Test
    fun `missing required translation is reported`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="one">One</string>
                <string name="two">Two</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings(
            "values-bg",
            """
            <resources>
                <string name="one">One translated</string>
            </resources>
            """.trimIndent(),
        )

        val offenders = LocalizationResourceLint.findLocalizationOffenders(resRoot)
        assertEquals(1, offenders.size)
        assertEquals("missing strings: two", offenders.single().message)
    }

    @Test
    fun `blank translation is reported`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="export_pdf_error">Couldn't generate PDF</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings(
            "values-es",
            """
            <resources>
                <string name="export_pdf_error">   </string>
            </resources>
            """.trimIndent(),
        )

        val offenders = LocalizationResourceLint.findLocalizationOffenders(resRoot)
        assertEquals(1, offenders.size)
        assertEquals("blank translations: export_pdf_error", offenders.single().message)
    }

    @Test
    fun `non translatable base strings are not required and are stale in locales`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="app_name" translatable="false">Riffle</string>
                <string name="export_pdf_error">Couldn't generate PDF</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings(
            "values-es",
            """
            <resources>
                <string name="app_name">Riffle</string>
                <string name="export_pdf_error">No se pudo generar el PDF</string>
            </resources>
            """.trimIndent(),
        )

        val offenders = LocalizationResourceLint.findLocalizationOffenders(resRoot)
        assertEquals(1, offenders.size)
        assertEquals("unexpected localized strings: app_name", offenders.single().message)
    }

    @Test
    fun `locale qualifiers only count directories that hold a strings file`() {
        writeStrings("values", "<resources><string name=\"one\">One</string></resources>")
        writeStrings("values-bg", "<resources><string name=\"one\">Едно</string></resources>")
        writeStrings("values-es", "<resources><string name=\"one\">Uno</string></resources>")
        resRoot.resolve("values-land").mkdirs()

        assertEquals(setOf("values-bg", "values-es"), LocalizationResourceLint.localeQualifiers(resRoot))
    }

    // The regression this lint was extended for: CacheSettingsRow/CacheSettingsDialog moved from
    // :app into feature/source-ui's commonMain, which had a composeResources/values/ folder and
    // nothing else, so Bulgarian and Spanish users saw English. Only scanning existing values-*
    // directories cannot see that — an absent locale directory has to be an offender in its own
    // right. This asserts it is.
    @Test
    fun `resource root missing a required locale directory entirely is reported`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="ui_cache_settings">Cache settings</string>
            </resources>
            """.trimIndent(),
        )

        val offenders = LocalizationResourceLint.findLocalizationOffenders(
            resRoot,
            requiredLocales = setOf("values-bg", "values-es"),
        )

        assertEquals(2, offenders.size)
        assertEquals(
            listOf("values-bg", "values-es"),
            offenders.map { it.file.parentFile.name },
        )
        assertTrue(offenders.all { it.message.startsWith("missing locale file") })
    }

    @Test
    fun `resource root covering every required locale has no offenders`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="ui_cache_settings">Cache settings</string>
                <string name="source_komga_name" translatable="false">Komga</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings("values-bg", "<resources><string name=\"ui_cache_settings\">Настройки на кеша</string></resources>")
        writeStrings("values-es", "<resources><string name=\"ui_cache_settings\">Ajustes de caché</string></resources>")

        val offenders = LocalizationResourceLint.findLocalizationOffenders(
            resRoot,
            requiredLocales = setOf("values-bg", "values-es"),
        )

        assertTrue(offenders.toString(), offenders.isEmpty())
    }

    @Test
    fun `multi root scan skips absent roots and reports each present one`() {
        writeStrings(
            "values",
            """
            <resources>
                <string name="one">One</string>
            </resources>
            """.trimIndent(),
        )
        writeStrings("values-bg", "<resources><string name=\"one\">Едно</string></resources>")
        writeStrings("values-es", "<resources><string name=\"one\">Uno</string></resources>")

        val moduleRoot = tmp.root.resolve("feature/source-ui/${LocalizationResourceLint.COMPOSE_RESOURCES_PATH}")
        moduleRoot.resolve("values").mkdirs()
        moduleRoot.resolve("values/strings.xml")
            .writeText("<resources><string name=\"two\">Two</string></resources>")
        val neverCreatedRoot = tmp.root.resolve("core/models/${LocalizationResourceLint.COMPOSE_RESOURCES_PATH}")

        val offenders = LocalizationResourceLint.findLocalizationOffenders(
            listOf(moduleRoot, neverCreatedRoot),
            requiredLocales = LocalizationResourceLint.localeQualifiers(resRoot),
        )

        assertEquals(2, offenders.size)
        assertTrue(offenders.all { it.file.startsWith(moduleRoot) })
        assertEquals(listOf("values-bg", "values-es"), offenders.map { it.file.parentFile.name })
    }

    private fun writeStrings(directory: String, body: String) {
        val file = resRoot.resolve("$directory/strings.xml")
        file.parentFile.mkdirs()
        file.writeText("""<?xml version="1.0" encoding="utf-8"?>""" + "\n" + body)
    }
}
