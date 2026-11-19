package com.riffle.core.data

import com.riffle.core.common.FileStore
import java.io.File

/** Android extension of [CrashReportRepositoryImpl] that resolves report ids to [File] objects
 *  for ACTION_SEND_MULTIPLE intents (the common interface returns String paths). */
class AndroidCrashReportRepositoryImpl(
    fileStore: FileStore,
) : CrashReportRepositoryImpl(fileStore) {

    fun resolveReportFiles(ids: List<String>): List<File> =
        ids.mapNotNull { id ->
            val f = File(reportPath(id))
            f.takeIf { it.isFile }
        }
}
