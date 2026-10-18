package com.riffle.buildlogic

/**
 * Pure detector for the iOS parity closing gate.
 *
 * Enforces that every `Closes #N` issue referenced in a PR body carries `Acceptance:` lines
 * naming existing, CI-wired tests on the iOS code path. Without this gate, issues closed on
 * merge re-surface in the next parity pass because the iOS behaviour was never verified.
 *
 * Three violation kinds, all checkable without network access in unit tests:
 *   - MISSING_TEST      — the named test function does not exist in any wired source set
 *   - UNWIRED_MODULE    — the Kotlin test file is in a module absent from `.github/workflows/ios.yml`
 *   - SWIFT_NOT_IN_PBXPROJ — the Swift test file is not a member of any Xcode target
 */
object ParityClosingGateLint {

    // Matches "Closes #123", "Fixes #123", "Resolves #123" (case-insensitive)
    private val CLOSES_PATTERN = Regex(
        """(?i)\b(?:closes?|fixes?|resolves?)\s+#(\d+)""",
    )

    // Matches "Acceptance: `value`" or "Acceptance: value" anywhere in a line,
    // tolerating list prefixes ("- "), bold markers ("**"), and trailing backticks.
    private val ACCEPTANCE_PATTERN = Regex(
        """(?i)\bAcceptance\b[*:]+\s*`?([^`\r\n]+?)`?\s*$""",
        RegexOption.MULTILINE,
    )

    // Matches "@Test" (optionally qualified)
    private val TEST_ANNOTATION = Regex("""@(?:org\.junit\.)?Test\b""")

    // Matches the function name after a @Test annotation
    private val FUN_NAME = Regex("""\bfun\s+(?:`([^`]+)`|(\w+))\s*\(""")

    // commonTest or iosTest source sets where iOS-path Kotlin tests live
    private val IOS_TEST_SOURCESET = Regex("""(?:^|/)src/(?:commonTest|iosTest)/""")

    enum class ViolationKind {
        MISSING_TEST,
        UNWIRED_MODULE,
        SWIFT_NOT_IN_PBXPROJ,
        OPEN_SUB_ISSUES,
    }

    data class Violation(
        val issueNumber: Int,
        val acceptance: String,
        val kind: ViolationKind,
        val detail: String,
    ) {
        fun render(): String =
            "Issue #$issueNumber  Acceptance: $acceptance\n    → ${kind.name}: $detail"
    }

    /** Parses issue numbers from `Closes #N` / `Fixes #N` / `Resolves #N` lines in [prBody]. */
    fun parseClosedIssueNumbers(prBody: String): List<Int> =
        CLOSES_PATTERN.findAll(prBody)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
            .distinct()
            .sorted()

    /** Parses `Acceptance:` values from an issue body. */
    fun parseAcceptanceLines(issueBody: String): List<String> =
        ACCEPTANCE_PATTERN.findAll(issueBody)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** True when the acceptance value names a Swift test (contains `.swift` or `#`-less `*Tests`). */
    fun isSwiftAcceptance(acceptance: String): Boolean =
        ".swift" in acceptance

    /** Returns the Swift filename component of an acceptance value like `FooTests.swift#testBar`. */
    fun swiftFilenameFromAcceptance(acceptance: String): String =
        acceptance.substringBefore("#").trim()
            .let { if (it.endsWith(".swift")) it else "$it.swift" }

    /** Returns the test function name from `ClassName#methodName` or bare `methodName`. */
    fun functionNameFromAcceptance(acceptance: String): String =
        if ('#' in acceptance) acceptance.substringAfterLast('#').trim()
        else acceptance.substringAfterLast('.').trim()

    /**
     * Finds all commonTest/iosTest source files (by path) that declare a `@Test fun [name]`.
     *
     * [testSourceMap] maps project-relative path → file content. Only entries whose path matches
     * the iOS source-set pattern are inspected.
     */
    fun findKotlinTestFiles(functionName: String, testSourceMap: Map<String, String>): List<String> =
        testSourceMap
            .filter { (path, _) -> path.endsWith(".kt") && IOS_TEST_SOURCESET.containsMatchIn(path) }
            .filter { (_, content) -> containsTestFunction(content, functionName) }
            .keys
            .sorted()

    private fun containsTestFunction(source: String, name: String): Boolean {
        var searchFrom = 0
        while (true) {
            val annotation = TEST_ANNOTATION.find(source, searchFrom) ?: return false
            val declaration = FUN_NAME.find(source, annotation.range.last) ?: return false
            val found = declaration.groupValues[1].ifEmpty { declaration.groupValues[2] }
            if (found == name) return true
            searchFrom = declaration.range.last
        }
    }

    /**
     * Returns the module root (e.g. `core/common`) for a project-relative file path by
     * stripping the `/src/<sourceSet>/` suffix.
     */
    fun moduleRootFromPath(path: String): String? {
        val match = Regex("""^(.*)/src/[^/]+/""").find(path) ?: return null
        return match.groupValues[1]
    }

    /**
     * Returns true when [moduleRoot] (e.g. `core/common`) is listed in [iosYmlContent] as
     * `:<gradle-path>:iosSimulatorArm64Test`, where the gradle path is the module root with
     * `/` replaced by `:`.
     */
    fun isModuleCiWired(moduleRoot: String, iosYmlContent: String): Boolean {
        val gradlePath = ":${moduleRoot.replace('/', ':')}"
        return iosYmlContent.contains("$gradlePath:iosSimulatorArm64Test") ||
            iosYmlContent.contains("$gradlePath:commonTest")
    }

    /**
     * Returns true when [swiftFilename] (e.g. `FooTests.swift`) appears in [pbxprojContent] as a
     * build file or file reference (i.e. the file is a member of an Xcode target).
     */
    fun isSwiftFilePbxprojMember(swiftFilename: String, pbxprojContent: String): Boolean =
        pbxprojContent.contains("/* $swiftFilename */") ||
            pbxprojContent.contains("/* $swiftFilename in Sources */")

    // Returns null when all sub-issues are closed; callers pre-fetch open sub-issue numbers via GraphQL.
    fun checkOpenSubIssues(
        issueNumber: Int,
        openSubIssueNumbers: List<Int>,
    ): Violation? {
        if (openSubIssueNumbers.isEmpty()) return null
        val formatted = openSubIssueNumbers.joinToString(", ") { "#$it" }
        return Violation(
            issueNumber = issueNumber,
            acceptance = "",
            kind = ViolationKind.OPEN_SUB_ISSUES,
            detail = "issue #$issueNumber has open sub-issues: $formatted — use `Part of #$issueNumber` instead of `Closes #$issueNumber` until all sub-issues are closed",
        )
    }

    /**
     * Checks all `Acceptance:` lines in [issueBody] for issue [issueNumber].
     * Returns a list of [Violation]s — empty means all acceptance tests are properly wired.
     *
     * @param testSourceMap project-relative path → content, for all Kotlin test source files
     * @param iosYmlContent raw content of `.github/workflows/ios.yml`
     * @param pbxprojContent raw content of `iosApp/iosApp.xcodeproj/project.pbxproj`
     */
    fun checkAcceptanceTests(
        issueNumber: Int,
        issueBody: String,
        testSourceMap: Map<String, String>,
        iosYmlContent: String,
        pbxprojContent: String,
    ): List<Violation> {
        val acceptanceLines = parseAcceptanceLines(issueBody)
        if (acceptanceLines.isEmpty()) return emptyList()

        return acceptanceLines.flatMap { acceptance ->
            checkSingleAcceptance(
                issueNumber = issueNumber,
                acceptance = acceptance,
                testSourceMap = testSourceMap,
                iosYmlContent = iosYmlContent,
                pbxprojContent = pbxprojContent,
            )
        }
    }

    private fun checkSingleAcceptance(
        issueNumber: Int,
        acceptance: String,
        testSourceMap: Map<String, String>,
        iosYmlContent: String,
        pbxprojContent: String,
    ): List<Violation> {
        if (isSwiftAcceptance(acceptance)) {
            val filename = swiftFilenameFromAcceptance(acceptance)
            return if (isSwiftFilePbxprojMember(filename, pbxprojContent)) {
                emptyList()
            } else {
                listOf(
                    Violation(
                        issueNumber = issueNumber,
                        acceptance = acceptance,
                        kind = ViolationKind.SWIFT_NOT_IN_PBXPROJ,
                        detail = "$filename is not referenced in project.pbxproj — add it to the correct Xcode target",
                    ),
                )
            }
        }

        val functionName = functionNameFromAcceptance(acceptance)
        val matchingFiles = findKotlinTestFiles(functionName, testSourceMap)
        if (matchingFiles.isEmpty()) {
            return listOf(
                Violation(
                    issueNumber = issueNumber,
                    acceptance = acceptance,
                    kind = ViolationKind.MISSING_TEST,
                    detail = "no @Test fun $functionName found in any commonTest or iosTest source set",
                ),
            )
        }

        return matchingFiles.flatMap { path ->
            val moduleRoot = moduleRootFromPath(path)
                ?: return@flatMap emptyList<Violation>()
            if (isModuleCiWired(moduleRoot, iosYmlContent)) {
                emptyList()
            } else {
                listOf(
                    Violation(
                        issueNumber = issueNumber,
                        acceptance = acceptance,
                        kind = ViolationKind.UNWIRED_MODULE,
                        detail = "$path is in module `$moduleRoot` which is absent from .github/workflows/ios.yml — add `:${moduleRoot.replace('/', ':')}:iosSimulatorArm64Test` to the ios.yml run step",
                    ),
                )
            }
        }.let { violations ->
            // If at least one matching file is properly wired, the acceptance test passes.
            if (violations.size < matchingFiles.size) emptyList() else violations
        }
    }
}
