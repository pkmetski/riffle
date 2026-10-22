package com.riffle.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalForeignApi::class)
class IosDictConverterTest {

    private val tmpFiles = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        tmpFiles.clear()
    }

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private fun tmpPath(suffix: String): String {
        val path = NSTemporaryDirectory() + "dict_converter_test_${NSUUID().UUIDString}$suffix"
        tmpFiles += path
        return path
    }

    private fun writeJsonl(lines: List<String>): String {
        val path = tmpPath(".jsonl")
        val content = lines.joinToString("\n") + "\n"
        val data: NSData = NSString.create(string = content).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("UTF-8 encode failed")
        data.writeToFile(path, atomically = true)
        return path
    }

    private fun makeEntry(word: String, pos: String, gloss: String): String =
        """{"word":"$word","pos":"$pos","senses":[{"glosses":["$gloss"]}]}"""

    @Test
    fun basicConversion_threeDistinctEntries() {
        val jsonl = writeJsonl(
            listOf(
                makeEntry("apple", "noun", "a round fruit"),
                makeEntry("run", "verb", "to move quickly"),
                makeEntry("run", "noun", "an act of running"),
            ),
        )
        val dbPath = tmpPath(".db")
        tmpFiles += dbPath
        createDictSqliteFromJsonl(jsonl, dbPath)
        assertEquals(3L, countDictSqliteEntries(dbPath))
    }

    @Test
    fun samePosAcrossMultipleBufferFlushes_glossesMergedAsValidJson() {
        // Generate enough entries to force multiple buffer flushes (buffer size = 2000).
        // "apple/noun" appears in the first and last batch so the cross-flush merge path fires.
        val filler = (1..2_002).map { makeEntry("word$it", "noun", "gloss $it") }
        val lines = listOf(makeEntry("apple", "noun", "first gloss")) +
            filler +
            listOf(makeEntry("apple", "noun", "second gloss"))
        val jsonl = writeJsonl(lines)
        val dbPath = tmpPath(".db")
        tmpFiles += dbPath

        createDictSqliteFromJsonl(jsonl, dbPath)

        val conn = BundledSQLiteDriver().open(dbPath)
        try {
            val stmt = conn.prepare("SELECT glosses FROM entries WHERE form = 'apple' AND pos = 'noun'")
            if (!stmt.step()) fail("apple/noun not found after cross-flush merge")
            val glosses = stmt.getText(0)
            stmt.close()

            assertTrue(glosses.startsWith("["), "glosses should be a JSON array: $glosses")
            assertTrue(glosses.endsWith("]"), "glosses should close with ]: $glosses")
            assertTrue(glosses.contains("first gloss"), "glosses should contain 'first gloss': $glosses")
            assertTrue(glosses.contains("second gloss"), "glosses should contain 'second gloss': $glosses")
        } finally {
            conn.close()
        }
    }

    @Test
    fun emptyOrBlankLinesAreSkipped() {
        val jsonl = writeJsonl(
            listOf(
                "",
                "   ",
                makeEntry("hello", "intj", "a greeting"),
                "",
            ),
        )
        val dbPath = tmpPath(".db")
        tmpFiles += dbPath
        createDictSqliteFromJsonl(jsonl, dbPath)
        assertEquals(1L, countDictSqliteEntries(dbPath))
    }

    @Test
    fun allBlankJsonl_throwsIllegalStateException() {
        val jsonl = writeJsonl(listOf("   ", ""))
        val dbPath = tmpPath(".db")
        tmpFiles += dbPath
        try {
            createDictSqliteFromJsonl(jsonl, dbPath)
            fail("Expected IllegalStateException for blank JSONL")
        } catch (_: IllegalStateException) {}
    }
}
