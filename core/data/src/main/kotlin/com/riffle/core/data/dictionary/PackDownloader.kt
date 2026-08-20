package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.LanguageCatalogEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class PackDownloader @Inject constructor(
    private val filesDir: File,
    private val httpClient: HttpClient,
    private val dictionaryPackDao: DictionaryPackDao,
    private val clock: Clock,
    private val converter: JsonlToSqliteConverter,
) {
    suspend fun download(
        entry: LanguageCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        val dictsDir = File(filesDir, "dicts").also { it.mkdirs() }
        val tmpJsonFile = File(dictsDir, "${entry.languageTag}.tmp.json")
        val tmpDbFile = File(dictsDir, "${entry.languageTag}.tmp.db")
        val finalFile = File(dictsDir, "${entry.languageTag}.db")

        dictionaryPackDao.upsert(
            DictionaryPackEntity(
                languageTag = entry.languageTag,
                packVersion = "",
                installedAt = clock.nowMs(),
                sizeBytes = entry.approximateSizeBytes,
                attributionHtml = entry.attributionHtml,
                licenseUrl = entry.licenseUrl,
                state = DictionaryPackState.DOWNLOADING.name,
            )
        )

        return try {
            // 1. Download JSONL
            val downloaded = httpClient.prepareGet(entry.jsonlUrl).execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute false
                }
                val totalBytes = response.headers["Content-Length"]?.toLong()
                    ?: entry.approximateSizeBytes
                var bytesRead = 0L
                val channel = response.bodyAsChannel()
                tmpJsonFile.outputStream().use { out ->
                    val buffer = ByteArray(65_536)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                }
                true
            }
            if (!downloaded) {
                tmpJsonFile.delete()
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            // 2. Convert JSONL → SQLite — report progress against the JSONL file size.
            tmpDbFile.delete()
            try {
                converter.convert(tmpJsonFile, tmpDbFile, onProgress)
            } catch (e: CancellationException) {
                tmpJsonFile.delete()
                tmpDbFile.delete()
                throw e
            } catch (_: Exception) {
                tmpJsonFile.delete()
                tmpDbFile.delete()
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }
            tmpJsonFile.delete()

            // 3. Atomic rename to final location
            if (!tmpDbFile.renameTo(finalFile)) {
                tmpDbFile.delete()
                dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            val packVersion = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.nowMs()))
            dictionaryPackDao.upsert(
                DictionaryPackEntity(
                    languageTag = entry.languageTag,
                    packVersion = packVersion,
                    installedAt = clock.nowMs(),
                    sizeBytes = finalFile.length(),
                    attributionHtml = entry.attributionHtml,
                    licenseUrl = entry.licenseUrl,
                    state = DictionaryPackState.INSTALLED.name,
                )
            )
            true
        } catch (e: CancellationException) {
            tmpJsonFile.delete()
            tmpDbFile.delete()
            finalFile.delete()
            throw e
        } catch (e: Exception) {
            tmpJsonFile.delete()
            tmpDbFile.delete()
            finalFile.delete()
            dictionaryPackDao.updateState(entry.languageTag, DictionaryPackState.FAILED.name)
            false
        }
    }
}
