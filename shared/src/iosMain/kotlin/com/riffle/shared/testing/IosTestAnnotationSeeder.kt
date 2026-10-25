package com.riffle.shared.testing

import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.domain.AnnotationStore
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform
import platform.Foundation.NSProcessInfo

private const val ARGUMENT_PREFIX = "--RIFFLE_SEED_ANNOTATIONS="
private const val SEED_TEXT_PREFIX = "section text"
private const val SEED_CHAPTER = "chapter1.xhtml"

/**
 * Seeds highlight annotations before the first frame, using data from
 * `--RIFFLE_SEED_ANNOTATIONS=<count>|<itemId>` (see [TestSourceSeed] for the pattern).
 *
 * Called from `RiffleApp.init` right after [seedTestSourceFromLaunchArguments]; a no-op when the
 * argument is absent. The active source must already be committed at call time (i.e.
 * [seedTestSourceFromLaunchArguments] must run first).
 *
 * Used by iOS harness tests that need the Annotations tab visible and its search field populated
 * so the annotation-search results screen can be reached.
 */
@Suppress("unused") // Called from Swift: IosTestAnnotationSeederKt.seedTestAnnotationsFromLaunchArguments()
fun seedTestAnnotationsFromLaunchArguments() {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val raw = arguments.firstOrNull { it.startsWith(ARGUMENT_PREFIX) }
        ?.removePrefix(ARGUMENT_PREFIX) ?: return
    val parts = raw.split("|")
    if (parts.size < 2) return
    val count = parts[0].toIntOrNull() ?: return
    val itemId = parts[1]
    val koin = KoinPlatform.getKoin()
    val database: RiffleDatabaseAccess = koin.get()
    val annotationStore: AnnotationStore = koin.get()
    runBlocking {
        val sourceId = database.sourceDao().getActive()?.id ?: run {
            println("RIFFLE_SEED_ANNOTATIONS: no active source found — run seedTestSourceFromLaunchArguments first")
            return@runBlocking
        }
        repeat(count) { index ->
            annotationStore.createHighlight(
                sourceId = sourceId,
                itemId = itemId,
                cfi = "epubcfi(/6/2[chapter1]!/4/2/${index + 1}/1:0)",
                textSnippet = "$SEED_TEXT_PREFIX $index",
                chapterHref = SEED_CHAPTER,
                originFontFamily = "Georgia, serif",
            )
        }
        println("RIFFLE_SEED_ANNOTATIONS: seeded $count annotations for $itemId in source $sourceId")
    }
}
