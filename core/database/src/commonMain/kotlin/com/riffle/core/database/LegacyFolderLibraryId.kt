package com.riffle.core.database

/**
 * Reproduces a collision-free library identity for folders encountered by migration 52→53
 * without relying on a platform UUID implementation.
 */
internal fun legacyFolderLibraryId(sourceId: String, treeUri: String): String =
    buildString {
        append("local:folder:")
        appendStableComponent(sourceId)
        appendStableComponent(treeUri)
    }

private fun StringBuilder.appendStableComponent(value: String) {
    append(value.length.toString(16))
    append('-')
    value.forEach { character ->
        append(character.code.toString(16).padStart(4, '0'))
    }
    append('-')
}
