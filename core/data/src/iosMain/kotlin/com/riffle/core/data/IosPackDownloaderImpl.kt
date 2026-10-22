package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.common.FileStore
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.createDictSqliteFromJsonl
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry
import com.riffle.core.dictionary.PackDownloaderInterface
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager

private const val DICT_NAMESPACE = "dicts"

@OptIn(ExperimentalForeignApi::class)
internal class IosPackDownloaderImpl(
    private val fileStore: FileStore,
    private val httpClient: HttpClient,
    private val dictionaryPackDao: DictionaryPackDao,
    private val clock: Clock,
) : PackDownloaderInterface {

    override suspend fun download(
        entry: LanguageCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        val finalPath = fileStore.resolve(DICT_NAMESPACE, "${entry.languageTag}.db")
        val tmpJsonlPath = "$finalPath.tmp.jsonl"
        val tmpDbPath = "$finalPath.tmp.db"

        dictionaryPackDao.upsert(
            DictionaryPackEntity(
                languageTag = entry.languageTag,
                packVersion = "",
                installedAt = clock.nowMs(),
                sizeBytes = entry.approximateSizeBytes,
                attributionHtml = entry.attributionHtml,
                licenseUrl = entry.licenseUrl,
                state = DictionaryPackState.DOWNLOADING.name,
            ),
        )

        return try {
            val downloaded = downloadJsonl(entry, tmpJsonlPath, onProgress)
            if (!downloaded) {
                remove(tmpJsonlPath)
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            try {
                createDictSqliteFromJsonl(tmpJsonlPath, tmpDbPath, onProgress)
            } catch (e: CancellationException) {
                remove(tmpJsonlPath)
                remove(tmpDbPath)
                throw e
            } catch (_: Exception) {
                remove(tmpJsonlPath)
                remove(tmpDbPath)
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }
            remove(tmpJsonlPath)

            if (!IosItemFiles.move(tmpDbPath, finalPath)) {
                remove(tmpDbPath)
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            dictionaryPackDao.upsert(
                DictionaryPackEntity(
                    languageTag = entry.languageTag,
                    packVersion = epochDateString(clock.nowMs()),
                    installedAt = clock.nowMs(),
                    sizeBytes = IosItemFiles.size(finalPath),
                    attributionHtml = entry.attributionHtml,
                    licenseUrl = entry.licenseUrl,
                    state = DictionaryPackState.INSTALLED.name,
                ),
            )
            true
        } catch (e: CancellationException) {
            remove(tmpJsonlPath)
            remove(tmpDbPath)
            throw e
        } catch (_: Exception) {
            remove(tmpJsonlPath)
            remove(tmpDbPath)
            dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
            false
        }
    }

    private suspend fun downloadJsonl(
        entry: LanguageCatalogEntry,
        path: String,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = httpClient.prepareGet(entry.jsonlUrl).execute { response ->
        if (!response.status.isSuccess()) return@execute false
        val total = response.headers["Content-Length"]?.toLong() ?: entry.approximateSizeBytes
        IosItemFiles.writeChannel(path, response.bodyAsChannel(), total, onProgress)
    }

    private fun remove(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

private fun epochDateString(epochMs: Long): String {
    val z = epochMs / 86_400_000L + 719_468L
    val era = if (z >= 0) z else z - 146_096L
    val doe = (era % 146_097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + (era / 146_097L) * 400L
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return "${year.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
