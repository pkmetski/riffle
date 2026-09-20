package com.riffle.buildlogic

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks localized string resources against their default values/strings.xml file.
 *
 * Two resource layouts are scanned, because both ship user-facing copy into the Android app:
 *
 *  - `app/src/main/res` — the classic Android resource tree, resolved through `R.string`.
 *  - `<module>/src/commonMain/composeResources` — Compose Multiplatform resources in a library
 *    module. These are packaged into every consumer (`:app` and `:shared`) and resolved against
 *    the system locale exactly like Android resources, so a `composeResources` directory with a
 *    `values/` folder and no `values-bg`/`values-es` renders English to Bulgarian and Spanish
 *    users. That is precisely how the shared `CacheSettingsRow`/`CacheSettingsDialog` regressed
 *    when they moved out of `:app`: the English copy came along, the translations did not, and
 *    the app-only lint stayed green because the orphaned `:app` entries were still translated.
 *
 * The Gradle task is intentionally thin; keeping the parser here makes the translation
 * completeness rule unit-testable without running Android tooling.
 */
object LocalizationResourceLint {

    /** Where a module keeps its shared Compose Multiplatform resources, relative to its dir. */
    const val COMPOSE_RESOURCES_PATH = "src/commonMain/composeResources"

    data class Offender(
        val file: File,
        val message: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)} — $message"
    }

    /**
     * Locale qualifiers (`values-bg`, `values-es`, …) that [resRoot] actually translates — i.e.
     * `values-*` directories holding a `strings.xml`. `values-land` and friends are ignored.
     *
     * The app's set is the project's canonical locale list; every other resource root has to
     * cover it (see the `requiredLocales` parameter of [findLocalizationOffenders]).
     */
    fun localeQualifiers(resRoot: File): Set<String> =
        resRoot
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.resolve("strings.xml").exists() }
            .map { it.name }
            .toSet()

    /** Runs [findLocalizationOffenders] over several resource roots, skipping ones that do not exist. */
    fun findLocalizationOffenders(resRoots: Collection<File>, requiredLocales: Set<String>): List<Offender> =
        resRoots
            .filter { it.isDirectory }
            .sortedBy { it.path }
            .flatMap { findLocalizationOffenders(it, requiredLocales) }

    fun findLocalizationOffenders(resRoot: File, requiredLocales: Set<String> = emptySet()): List<Offender> {
        val defaultFile = resRoot.resolve("values/strings.xml")
        if (!defaultFile.exists()) return emptyList()

        val requiredNames = requiredStringNames(defaultFile)
        val presentQualifiers = localeQualifiers(resRoot)
        val localeFiles = presentQualifiers
            .map { resRoot.resolve("$it/strings.xml") }
            .sortedBy { it.path }

        // A wholly absent locale directory is the regression this lint exists to catch: the
        // per-file checks below can only speak about locales that are already there.
        val absentLocales = (requiredLocales - presentQualifiers)
            .sorted()
            .map { qualifier ->
                Offender(
                    resRoot.resolve("$qualifier/strings.xml"),
                    "missing locale file — this resource root ships user-facing copy but has no " +
                        "$qualifier translation, so those users see the untranslated values/ copy",
                )
            }

        return absentLocales + localeFiles.flatMap { localeFile ->
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
