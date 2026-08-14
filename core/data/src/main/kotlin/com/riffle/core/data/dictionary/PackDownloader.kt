package com.riffle.core.data.dictionary

import com.riffle.core.common.Clock
import com.riffle.core.database.DictionaryPackDao
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.dictionary.DictionaryPackState
import com.riffle.core.dictionary.PackInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

class PackDownloader @Inject constructor(
    private val filesDir: File,
    private val httpClient: HttpClient,
    private val dictionaryPackDao: DictionaryPackDao,
    private val clock: Clock,
) {
    suspend fun download(packInfo: PackInfo): Boolean {
        val dictsDir = File(filesDir, "dicts").also { it.mkdirs() }
        val tmpFile = File(dictsDir, "${packInfo.languageTag}.tmp")
        val finalFile = File(dictsDir, "${packInfo.languageTag}.db")

        dictionaryPackDao.updateState(packInfo.languageTag, DictionaryPackState.DOWNLOADING.name)

        return try {
            val ok = httpClient.prepareGet(packInfo.downloadUrl).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                val channel = response.bodyAsChannel()
                tmpFile.outputStream().use { out ->
                    val buffer = ByteArray(65_536)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
                true
            }
            if (!ok) {
                tmpFile.delete()
                dictionaryPackDao.updateState(packInfo.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            val actualSha256 = sha256Hex(tmpFile)
            if (!actualSha256.equals(packInfo.sha256, ignoreCase = true)) {
                tmpFile.delete()
                dictionaryPackDao.updateState(packInfo.languageTag, DictionaryPackState.FAILED.name)
                return false
            }

            tmpFile.renameTo(finalFile)

            dictionaryPackDao.upsert(
                DictionaryPackEntity(
                    languageTag = packInfo.languageTag,
                    packVersion = packInfo.packVersion,
                    installedAt = clock.nowMs(),
                    sizeBytes = finalFile.length(),
                    attributionHtml = packInfo.attributionHtml,
                    licenseUrl = packInfo.licenseUrl,
                    state = DictionaryPackState.INSTALLED.name,
                )
            )
            true
        } catch (_: Exception) {
            tmpFile.delete()
            dictionaryPackDao.updateState(packInfo.languageTag, DictionaryPackState.FAILED.name)
            false
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
