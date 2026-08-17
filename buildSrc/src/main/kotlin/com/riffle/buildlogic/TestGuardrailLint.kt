package com.riffle.buildlogic

/**
 * Pure detector for disappearing `@Test` functions. Analogue of `RiffleLogTagLint` —
 * the gradle task `checkTestGuardrails` is a thin git-plumbing wrapper around these
 * functions, keeping the logic JUnit-testable.
 *
 * Every test assertion is a behavioral claim someone once made; deleting or renaming a
 * test silently retires that claim. The chapter-map section-flood regression shipped
 * exactly this way: three tests pinning "same-file sections collapse to one chapter
 * segment" were renamed to assert the opposite inside a 300-line feature diff, and the
 * removal was invisible in review. This lint turns every disappearance of a test
 * function (relative to the merge base with main) into an explicit, declared event: the
 * branch must carry a `Removed-test: <exact test name>` line in a commit message, which
 * surfaces the retired claim in `git log` and the PR conversation.
 */
object TestGuardrailLint {

    /** Matches `@Test` (optionally fully qualified) but not e.g. `@TestOnly`. */
    private val TEST_ANNOTATION = Regex("""@(?:org\.junit\.)?Test\b""")

    /** The next function declaration after a `@Test` annotation, backticked or plain. */
    private val FUN_NAME = Regex("""\bfun\s+(?:`([^`]+)`|(\w+))\s*\(""")

    private val REMOVED_TEST_TRAILER = Regex("""^Removed-test:\s*(.+)$""", RegexOption.MULTILINE)

    private val TEST_SOURCE_DIR = Regex("""(^|/)src/(test|androidTest|androidDeviceTest|jvmTest|commonTest)/""")

    /** Kotlin files under a test source set are subject to the guardrail. */
    fun isTestSourceFile(path: String): Boolean =
        path.endsWith(".kt") && TEST_SOURCE_DIR.containsMatchIn(path)

    /** Names of all `@Test` functions declared in [source]. */
    fun extractTestNames(source: String): Set<String> {
        val names = mutableSetOf<String>()
        var searchFrom = 0
        while (true) {
            val annotation = TEST_ANNOTATION.find(source, searchFrom) ?: break
            val declaration = FUN_NAME.find(source, annotation.range.last) ?: break
            names += declaration.groupValues[1].ifEmpty { declaration.groupValues[2] }
            searchFrom = declaration.range.last
        }
        return names
    }

    /**
     * Test names declared as intentionally removed via `Removed-test:` trailer lines in
     * [commitMessages] (the concatenated bodies of every commit on the branch). Backticks
     * around the name are tolerated so the trailer can be pasted from the test source.
     */
    fun parseDeclaredRemovals(commitMessages: String): Set<String> =
        REMOVED_TEST_TRAILER.findAll(commitMessages)
            .map { it.groupValues[1].trim().removeSurrounding("`") }
            .toSet()

    data class RemovedTest(val file: String, val name: String) {
        fun render(): String = "$name  (was in $file)"
    }

    /**
     * Test names present in the old tree but absent from the new one, minus [declared].
     * Comparison is global across files, so moving a test between files is not flagged —
     * only its disappearance from the branch is.
     */
    fun findUndeclaredRemovals(
        oldTestsByFile: Map<String, Set<String>>,
        newTestsByFile: Map<String, Set<String>>,
        declared: Set<String>,
    ): List<RemovedTest> {
        val newNames = newTestsByFile.values.flatten().toSet()
        return oldTestsByFile
            .flatMap { (file, names) -> names.map { RemovedTest(file, it) } }
            .filterNot { it.name in newNames || it.name in declared }
            .sortedWith(compareBy({ it.file }, { it.name }))
    }
}
