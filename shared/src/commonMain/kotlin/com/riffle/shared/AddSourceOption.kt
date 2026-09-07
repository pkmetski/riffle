package com.riffle.shared

/**
 * Source-add flows implemented in the shared (iOS) app shell. The empty start destination and
 * the Settings "Sources" section render one entry per option; adding a flow here requires a
 * screen behind it.
 */
enum class AddSourceOption(val label: String) {
    Audiobookshelf("Add Audiobookshelf Server"),
    LocalFiles("Add Local Files"),
}

/**
 * The options every add-source surface must offer. Extracted from the Composables so a test can
 * pin the set: PR #908 replaced the empty state with a Local-Files-only screen and silently
 * removed the only way to add an Audiobookshelf server on iOS.
 */
fun availableAddSourceOptions(): List<AddSourceOption> =
    listOf(AddSourceOption.Audiobookshelf, AddSourceOption.LocalFiles)
