package com.riffle.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

abstract class CheckTranslationsTask : DefaultTask() {

    @get:InputDirectory
    abstract val resRoot: DirectoryProperty

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun checkTranslations() {
        val offenders = LocalizationResourceLint.findLocalizationOffenders(resRoot = resRoot.asFile.get())
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Localized string resources must match app/src/main/res/values/strings.xml.\n" +
                    "Use `./gradlew createTranslation -Plocale=<tag>` to scaffold a locale, then fill every blank value:\n" +
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
