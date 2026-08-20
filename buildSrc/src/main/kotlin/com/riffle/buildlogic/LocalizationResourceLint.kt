package com.riffle.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks localized Android string resources against the default values/strings.xml file.
 *
 * The Gradle task is intentionally thin; keeping the parser here makes the translation
 * completeness rule unit-testable without running Android tooling.
 */
object LocalizationResourceLint {

    data class Offender(
        val file: File,
        val message: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)} — $message"
    }

    fun findLocalizationOffenders(resRoot: File): List<Offender> {
        val defaultFile = resRoot.resolve("values/strings.xml")
        if (!defaultFile.exists()) return emptyList()

        val requiredNames = requiredStringNames(defaultFile)
        val localeFiles = resRoot
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.resolve("strings.xml") }
            .filter { it.exists() }
            .sortedBy { it.path }
            .toList()

        return localeFiles.flatMap { localeFile ->
            val localized = parseStringResources(localeFile)
            val missing = requiredNames - localized.keys
            val blank = localized
                .filterKeys { it in requiredNames }
                .filterValues { it.value.isBlank() }
                .keys
            val stale = localized.keys - requiredNames

            buildList {
                if (missing.isNotEmpty()) {
                    add(Offender(localeFile, "missing strings: ${missing.sorted().joinToString()}"))
                }
                if (blank.isNotEmpty()) {
                    add(Offender(localeFile, "blank translations: ${blank.sorted().joinToString()}"))
                }
                if (stale.isNotEmpty()) {
                    add(Offender(localeFile, "unexpected localized strings: ${stale.sorted().joinToString()}"))
                }
            }
        }
    }

    fun requiredStringNames(defaultFile: File): Set<String> =
        parseStringResources(defaultFile).filterValues { it.translatable }.keys

    fun localizedStringNames(stringsFile: File): Set<String> =
        if (stringsFile.exists()) parseStringResources(stringsFile).keys else emptySet()

    private fun parseStringResources(file: File): Map<String, StringResource> {
        val document = documentBuilderFactory().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until strings.length) {
                val element = strings.item(i) as Element
                val name = element.getAttribute("name")
                if (name.isBlank()) continue
                val translatable = element.getAttribute("translatable") != "false"
                put(name, StringResource(value = element.textContent.orEmpty(), translatable = translatable))
            }
        }
    }

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }

    private data class StringResource(
        val value: String,
        val translatable: Boolean,
    )
}
