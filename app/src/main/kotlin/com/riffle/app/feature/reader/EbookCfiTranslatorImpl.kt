package com.riffle.app.feature.reader

import com.riffle.core.domain.EbookCfiTranslator
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Headless CFI↔Locator translator backed by a cached EPUB ZIP (ADR 0013). Parses the OPF spine
 * once on first use; caches chapter HTML per spine index. Thread-safe (synchronized lazy init),
 * but designed to be short-lived (one per sweep item, not a singleton).
 */
internal class EbookCfiTranslatorImpl(private val epubFile: File) : EbookCfiTranslator {

    @Volatile private var zipFile: ZipFile? = null
    @Volatile private var spineHrefs: List<String>? = null
    private val htmlCache = mutableMapOf<Int, String>()

    override suspend fun cfiToLocatorJson(epubcfi: String): String? {
        if (!epubcfi.startsWith("epubcfi(")) return null
        val spineIndex = epubCfiToSpineIndex(epubcfi) ?: return null
        val hrefs = ensureSpine() ?: return null
        val href = hrefs.getOrNull(spineIndex) ?: return null
        val html = readChapterHtml(hrefs, spineIndex) ?: return null
        val docPath = extractCfiDocPath(epubcfi) ?: return null
        val progression = cfiDocPathToProgression(docPath, html) ?: return null
        return JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("progression", progression))
            .toString()
    }

    override suspend fun locatorJsonToCfi(locatorJson: String): String? {
        if (locatorJson.startsWith("epubcfi(")) return locatorJson
        val json = runCatching { JSONObject(locatorJson) }.getOrNull() ?: return null
        val href = json.optString("href").takeIf { it.isNotBlank() } ?: return null
        val progression = json.optJSONObject("locations")?.optDouble("progression")
            ?.takeIf { !it.isNaN() } ?: return null
        val hrefs = ensureSpine() ?: return null
        val spineIndex = hrefs.indexOfFirst {
            normalizeEpubHref(it) == normalizeEpubHref(href)
        }
        if (spineIndex < 0) return null
        val html = readChapterHtml(hrefs, spineIndex) ?: return null
        val docPath = progressionToCfiDocPath(progression, html) ?: return null
        return "epubcfi(/6/${(spineIndex + 1) * 2}!$docPath)"
    }

    private fun ensureZip(): ZipFile? {
        zipFile?.let { return it }
        return synchronized(this) {
            zipFile ?: runCatching { ZipFile(epubFile) }.getOrNull()?.also { zipFile = it }
        }
    }

    private fun ensureSpine(): List<String>? {
        spineHrefs?.let { return it }
        return synchronized(this) {
            spineHrefs ?: parseSpineHrefs(ensureZip() ?: return null)?.also { spineHrefs = it }
        }
    }

    private fun readChapterHtml(hrefs: List<String>, spineIndex: Int): String? {
        htmlCache[spineIndex]?.let { return it }
        val z = ensureZip() ?: return null
        val entryPath = normalizeEpubHref(hrefs[spineIndex])
        return runCatching {
            z.getEntry(entryPath)?.let { z.getInputStream(it).bufferedReader().readText() }
        }.getOrNull()?.also { htmlCache[spineIndex] = it }
    }

    /** Reads META-INF/container.xml → OPF → spine, returning hrefs relative to the EPUB root. */
    private fun parseSpineHrefs(zip: ZipFile): List<String>? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        val xmlFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val builder = runCatching { xmlFactory.newDocumentBuilder() }.getOrNull() ?: return null

        val rootfilePath = runCatching {
            zip.getInputStream(containerEntry).use { stream ->
                builder.parse(stream)
                    .getElementsByTagName("rootfile").item(0)
                    ?.attributes?.getNamedItem("full-path")?.textContent
            }
        }.getOrNull() ?: return null

        val opfEntry = zip.getEntry(rootfilePath) ?: return null
        val opfDir = rootfilePath.substringBeforeLast('/', "")

        return runCatching {
            zip.getInputStream(opfEntry).use { stream ->
                val doc = builder.parse(stream)

                val manifest = mutableMapOf<String, String>()
                val items = doc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val attrs = items.item(i).attributes ?: continue
                    val id = attrs.getNamedItem("id")?.textContent ?: continue
                    val href = attrs.getNamedItem("href")?.textContent ?: continue
                    manifest[id] = href
                }

                val itemrefs = doc.getElementsByTagName("itemref")
                (0 until itemrefs.length).mapNotNull { i ->
                    val idref = itemrefs.item(i).attributes
                        ?.getNamedItem("idref")?.textContent ?: return@mapNotNull null
                    val rawHref = manifest[idref] ?: return@mapNotNull null
                    if (opfDir.isEmpty()) rawHref else "$opfDir/$rawHref"
                }
            }
        }.getOrNull()
    }
}
