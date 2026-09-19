package com.riffle.core.domain

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [IosEbookCfiTranslator] against a real (hand-built, in-memory) EPUB ZIP
 * on disk — exercises ZIP central-directory parsing ([IosZipArchive]), container.xml/OPF regex
 * parsing (spine resolution), and the ksoup DOM-walking translation together, the way
 * [com.riffle.shared.reader.IosEbookCfiTranslatorFactory] wires it in production. Mirrors the
 * scenarios in app/src/androidTest's EpubCfiTranslatorInstrumentedTest, but builds its own minimal
 * fixture instead of depending on the androidTest-only test.epub asset.
 */
@OptIn(ExperimentalForeignApi::class)
class IosEbookCfiTranslatorTest {

    private val chapter1Html = "<html><body><p>Hello world</p><p>Second paragraph</p></body></html>"
    private val chapter2Html = "<html><body><p id=\"c2\">Chapter two content here</p></body></html>"

    private var epubPath: String? = null

    @AfterTest
    fun cleanup() {
        epubPath?.let { platform.posix.remove(it) }
    }

    private fun buildTestEpub(): String {
        val containerXml = """
            <?xml version="1.0"?>
            <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
        """.trimIndent()
        val opfXml = """
            <?xml version="1.0"?>
            <package>
              <manifest>
                <item id="ch1" href="chapter1.xhtml"/>
                <item id="ch2" href="chapter2.xhtml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        val entries = listOf(
            "META-INF/container.xml" to containerXml.encodeToByteArray(),
            "OEBPS/content.opf" to opfXml.encodeToByteArray(),
            "OEBPS/chapter1.xhtml" to chapter1Html.encodeToByteArray(),
            "OEBPS/chapter2.xhtml" to chapter2Html.encodeToByteArray(),
        )
        val zipBytes = buildStoredZip(entries)

        val path = NSTemporaryDirectory() + "cfi_test_" + NSUUID().UUIDString() + ".epub"
        writeFile(path, zipBytes)
        epubPath = path
        return path
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("failed to open $path for writing")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    // ── cfiToLocatorJson ──────────────────────────────────────────────────────

    @Test
    fun `cfiToLocatorJson resolves spine index to href and progression`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        // spine index 0 (chapter1) -> CFI spine step (0+1)*2 = 2
        val json = translator.cfiToLocatorJson("epubcfi(/6/2!/4/2/1:5)")
        assertNotNull(json)
        assertTrue(json.contains("chapter1.xhtml"), "expected chapter1 href in $json")
        // "Hello world"=11, "Second paragraph"=16, total=27; offset 5 -> 5/27
        assertTrue(json.contains("\"progression\":0.185"), "expected progression field in $json")
    }

    @Test
    fun `cfiToLocatorJson resolves second spine item`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        // spine index 1 (chapter2) -> CFI spine step (1+1)*2 = 4
        val json = translator.cfiToLocatorJson("epubcfi(/6/4!/4/2[c2]/1:0)")
        assertNotNull(json)
        assertTrue(json.contains("chapter2.xhtml"), "expected chapter2 href in $json")
    }

    @Test
    fun `cfiToLocatorJson returns null for malformed cfi`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        assertNull(translator.cfiToLocatorJson("not-a-cfi"))
    }

    @Test
    fun `cfiToLocatorJson returns null for spine index beyond spine`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        assertNull(translator.cfiToLocatorJson("epubcfi(/6/100!/4/2/1:0)"))
    }

    // ── locatorJsonToCfi ──────────────────────────────────────────────────────

    @Test
    fun `locatorJsonToCfi resolves href to spine-anchored cfi`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        val locatorJson = """{"href":"OEBPS/chapter1.xhtml","locations":{"progression":0.0}}"""
        val cfi = translator.locatorJsonToCfi(locatorJson)
        assertNotNull(cfi)
        assertTrue(cfi.startsWith("epubcfi(/6/2!"))
    }

    @Test
    fun `locatorJsonToCfi passes through an already-cfi input`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        val cfi = "epubcfi(/6/2!/4/2/1:0)"
        assertEquals(cfi, translator.locatorJsonToCfi(cfi))
    }

    @Test
    fun `locatorJsonToCfi returns null when href not in spine`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        val locatorJson = """{"href":"OEBPS/nonexistent.xhtml","locations":{"progression":0.0}}"""
        assertNull(translator.locatorJsonToCfi(locatorJson))
    }

    // ── Round trip ────────────────────────────────────────────────────────────

    @Test
    fun `round trip cfi to locator to cfi lands on the same chapter`() = runTest {
        val translator = IosEbookCfiTranslator(buildTestEpub())
        val originalCfi = "epubcfi(/6/2!/4/4/1:3)"
        val locatorJson = translator.cfiToLocatorJson(originalCfi)!!
        val rebuiltCfi = translator.locatorJsonToCfi(locatorJson)!!
        assertTrue(rebuiltCfi.startsWith("epubcfi(/6/2!"))
        val reconvertedJson = translator.cfiToLocatorJson(rebuiltCfi)!!
        assertTrue(reconvertedJson.contains("chapter1.xhtml"))
    }
}

// ── Minimal in-memory STORED-only ZIP builder (no compression needed for tests: IosZipArchive
// reads COMPRESSION_STORED entries verbatim, matching what real EPUB producers rarely use but the
// reader still supports; CRC-32 is not validated by IosZipArchive so zeros are fine here). ──────

private fun buildStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val localSections = mutableListOf<ByteArray>()
    val centralSections = mutableListOf<ByteArray>()
    var offset = 0

    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val localHeader = buildList {
            addAll(le32(0x04034B50))
            addAll(le16(20)) // version needed
            addAll(le16(0)) // flags
            addAll(le16(0)) // method: stored
            addAll(le16(0)) // mod time
            addAll(le16(0)) // mod date
            addAll(le32(0)) // crc32 (unchecked by reader)
            addAll(le32(data.size)) // compressed size
            addAll(le32(data.size)) // uncompressed size
            addAll(le16(nameBytes.size))
            addAll(le16(0)) // extra field length
        }.toByteArray() + nameBytes + data
        localSections += localHeader

        val centralHeader = buildList {
            addAll(le32(0x02014B50))
            addAll(le16(20)) // version made by
            addAll(le16(20)) // version needed
            addAll(le16(0)) // flags
            addAll(le16(0)) // method: stored
            addAll(le16(0)) // mod time
            addAll(le16(0)) // mod date
            addAll(le32(0)) // crc32
            addAll(le32(data.size))
            addAll(le32(data.size))
            addAll(le16(nameBytes.size))
            addAll(le16(0)) // extra field length
            addAll(le16(0)) // comment length
            addAll(le16(0)) // disk number start
            addAll(le16(0)) // internal attrs
            addAll(le32(0)) // external attrs
            addAll(le32(offset)) // local header offset
        }.toByteArray() + nameBytes
        centralSections += centralHeader

        offset += localHeader.size
    }

    val centralDirectory = centralSections.reduce { acc, bytes -> acc + bytes }
    val centralDirOffset = offset
    val eocd = buildList {
        addAll(le32(0x06054B50))
        addAll(le16(0)) // disk number
        addAll(le16(0)) // disk with cd
        addAll(le16(entries.size)) // entries this disk
        addAll(le16(entries.size)) // total entries
        addAll(le32(centralDirectory.size)) // cd size
        addAll(le32(centralDirOffset)) // cd offset
        addAll(le16(0)) // comment length
    }.toByteArray()

    return localSections.reduce { acc, bytes -> acc + bytes } + centralDirectory + eocd
}

private fun le16(value: Int): List<Byte> = listOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
)

private fun le32(value: Int): List<Byte> = listOf(
    (value and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 24) and 0xFF).toByte(),
)
