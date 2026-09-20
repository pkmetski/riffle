package com.riffle.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CheckTranslationsTask : DefaultTask() {

    /** `app/src/main/res` — the canonical locale list comes from here. */
    @get:InputDirectory
    abstract val resRoot: DirectoryProperty

    /**
     * Every module's `src/commonMain/composeResources` directory, whether or not it exists yet.
     * Absolute paths, so [InputFiles] tracking lives on [composeResourceFiles] instead.
     */
    @get:Internal
    abstract val composeResourceRoots: ListProperty<String>

    /** Up-to-date tracking for the directories named by [composeResourceRoots]. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val composeResourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun checkTranslations() {
        val appResRoot = resRoot.asFile.get()
        val requiredLocales = LocalizationResourceLint.localeQualifiers(appResRoot)
        val composeRoots = composeResourceRoots.get().map(::File)

        val offenders = LocalizationResourceLint.findLocalizationOffenders(appResRoot) +
            LocalizationResourceLint.findLocalizationOffenders(composeRoots, requiredLocales)

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Localized string resources must match the values/strings.xml of their own resource root.\n" +
                    "For app/src/main/res use `./gradlew createTranslation -Plocale=<tag>` to scaffold a locale.\n" +
                    "For a module's src/commonMain/composeResources, add the values-<tag>/strings.xml by hand — " +
                    "those resources are packaged into both :app and :shared and follow the system locale, so an " +
                    "untranslated key renders English to every " +
                    requiredLocales.sorted().joinToString(" / ") { it.removePrefix("values-") } +
                    " user.\nThen fill every blank value:\n" +
                    offenders.joinToString("\n") { it.render(projectRoot.asFile.get()) },
            )
        }
    }
}

abstract class CreateTranslationTask : DefaultTask() {

    @get:InputDirectory
    abstract val resRoot: DirectoryProperty

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val locale: Property<String>

    @TaskAction
    fun createTranslation() {
        val localeTag = locale.orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Pass the locale tag with -Plocale=<tag>, for example -Plocale=fr or -Plocale=pt-rBR.")
        if (!Regex("""[a-z]{2,3}(-r[A-Z]{2})?""").matches(localeTag)) {
            throw GradleException("Locale must be an Android resource qualifier such as es, bg, or pt-rBR; got `$localeTag`.")
        }

        val resRootFile = resRoot.asFile.get()
        val projectRootFile = projectRoot.asFile.get()
        val defaultFile = resRootFile.resolve("values/strings.xml")
        val localeDir = resRootFile.resolve("values-$localeTag").also { it.mkdirs() }
        val localeFile = localeDir.resolve("strings.xml")
        val requiredNames = LocalizationResourceLint.requiredStringNames(defaultFile)
        val existingNames = LocalizationResourceLint.localizedStringNames(localeFile)
        val missingNames = (requiredNames - existingNames).sorted()

        if (!localeFile.exists()) {
            localeFile.writeText(
                buildString {
                    appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                    appendLine("<resources>")
                    missingNames.forEach { name ->
                        appendLine("""    <string name="$name"></string>""")
                    }
                    appendLine("</resources>")
                },
            )
            logger.lifecycle("Created ${localeFile.relativeTo(projectRootFile)} with ${missingNames.size} string(s).")
            return
        }

        if (missingNames.isEmpty()) {
            logger.lifecycle("${localeFile.relativeTo(projectRootFile)} already has all required string keys.")
            return
        }

        val original = localeFile.readText()
        val insertion = missingNames.joinToString(separator = "\n", postfix = "\n") { name ->
            """    <string name="$name"></string>"""
        }
        if (!original.contains("</resources>")) {
            throw GradleException("${localeFile.relativeTo(projectRootFile)} does not contain a closing </resources> tag.")
        }
        localeFile.writeText(original.replace("</resources>", "$insertion</resources>"))
        logger.lifecycle("Added ${missingNames.size} missing string(s) to ${localeFile.relativeTo(projectRootFile)}.")
    }
}
