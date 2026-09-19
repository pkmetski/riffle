package com.riffle.core.data

/**
 * The local-only library id that namespaces a Storyteller Source's readaloud rows in
 * `library_items` (matcher input; never browsable — ADR 0032).
 *
 * Shared so the Android installer, the Android `SourceRepositoryImpl` companion and the
 * multiplatform [StorytellerReadaloudSyncer] all derive the id from one definition — a drifted
 * prefix here would silently orphan every readaloud row a platform wrote.
 */
fun readaloudLibraryId(sourceId: String): String = "readaloud:$sourceId"
