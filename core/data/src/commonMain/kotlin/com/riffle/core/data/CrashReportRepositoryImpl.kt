package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.models.CrashReport
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Stores crash reports as `<reportDir>/<id>.txt` files and lists them newest-first by file
 * modification time. One file per crash so multiple crashes survive; the prior single-file design
 * overwrote on every crash.
 *
 * Platform-specific extensions (e.g. `resolveReportFiles(ids): List<java.io.File>` for Android
 * ACTION_SEND_MULTIPLE intents, `record(id, content)` for iOS Kotlin/Native crash hooks) live in
 * the respective source sets.
 */
open class CrashReportRepositoryImpl(
    private val fileStore: FileStore,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : CrashReportRepository {

    protected fun reportDir(): String = fileStore.resolve(NS_CRASH_REPORTS)

    protected fun reportPath(id: String): String = "${reportDir()}/$id.$EXT"

    override fun listCrashReports(): List<CrashReport> {
        val dir = reportDir().toPath()
        if (!fileSystem.exists(dir)) return emptyList()
        return runCatching { fileSystem.list(dir) }.getOrElse { emptyList() }
            .filter { p ->
                fileSystem.metadata(p).isRegularFile && p.name.endsWith(".$EXT")
            }
            .mapNotNull { p ->
                val id = p.name.removeSuffix(".$EXT")
                val content = runCatching { fileSystem.read(p) { readUtf8() } }.getOrNull() ?: return@mapNotNull null
                val modifiedAt = fileSystem.metadata(p).lastModifiedAtMillis ?: 0L
                CrashReport(id = id, content = content, timestampMillis = modifiedAt)
            }
            .sortedByDescending { it.timestampMillis }
    }

    override fun resolveReportFilePaths(ids: List<String>): List<String> =
        ids.mapNotNull { id ->
            val path = reportPath(id).toPath()
            if (fileSystem.exists(path) && fileSystem.metadata(path).isRegularFile) path.toString() else null
        }

    override fun clearAllCrashReports() {
        val dir = reportDir().toPath()
        if (!fileSystem.exists(dir)) return
        runCatching { fileSystem.list(dir) }.getOrElse { emptyList() }
            .filter { p -> p.name.endsWith(".$EXT") }
            .forEach { p -> runCatching { fileSystem.delete(p) } }
    }

    fun record(id: String, content: String) {
        val path = reportPath(id).toPath()
        runCatching { fileSystem.createDirectories(path.parent!!) }
        runCatching { fileSystem.write(path) { writeUtf8(content) } }
    }

    companion object {
        const val EXT = "txt"
    }
}
