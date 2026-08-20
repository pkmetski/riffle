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

    private fun writeStrings(directory: String, body: String) {
        val file = resRoot.resolve("$directory/strings.xml")
        file.parentFile.mkdirs()
        file.writeText("""<?xml version="1.0" encoding="utf-8"?>""" + "\n" + body)
    }
}
