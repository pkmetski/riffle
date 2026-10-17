package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityClosingGateLintTest {

    // ── parseClosedIssueNumbers ──────────────────────────────────────────────

    @Test
    fun `parses Closes and Fixes and Resolves references`() {
        val body = """
            Closes #123
            Fixes #456
            Resolves #789
        """.trimIndent()
        assertEquals(listOf(123, 456, 789), ParityClosingGateLint.parseClosedIssueNumbers(body))
    }

    @Test
    fun `ignores Part of references`() {
        val body = "Part of #100\nCloses #200"
        assertEquals(listOf(200), ParityClosingGateLint.parseClosedIssueNumbers(body))
    }

    @Test
    fun `returns empty list when no issue references found`() {
        assertEquals(emptyList<Int>(), ParityClosingGateLint.parseClosedIssueNumbers("No refs here."))
    }

    @Test
    fun `deduplicates repeated references`() {
        val body = "Closes #42\nCloses #42"
        assertEquals(listOf(42), ParityClosingGateLint.parseClosedIssueNumbers(body))
    }

    // ── parseAcceptanceLines ─────────────────────────────────────────────────

    @Test
    fun `parses backtick-wrapped acceptance value`() {
        val body = "- Acceptance: `FooTest#testBar`"
        assertEquals(listOf("FooTest#testBar"), ParityClosingGateLint.parseAcceptanceLines(body))
    }

    @Test
    fun `parses bare acceptance value`() {
        val body = "Acceptance: FooTest#testBar"
        assertEquals(listOf("FooTest#testBar"), ParityClosingGateLint.parseAcceptanceLines(body))
    }

    @Test
    fun `parses bold-prefixed acceptance value`() {
        val body = "**Acceptance:** `FooTests.swift#testBar`"
        assertEquals(listOf("FooTests.swift#testBar"), ParityClosingGateLint.parseAcceptanceLines(body))
    }

    @Test
    fun `returns empty list when no Acceptance lines present`() {
        val body = "Android evidence: FooTest.kt:42\niOS entry point: Settings"
        assertEquals(emptyList<String>(), ParityClosingGateLint.parseAcceptanceLines(body))
    }

    @Test
    fun `parses multiple Acceptance lines`() {
        val body = """
            - Acceptance: `FooTest#testA`
            - Acceptance: `BarTests.swift#testB`
        """.trimIndent()
        assertEquals(
            listOf("FooTest#testA", "BarTests.swift#testB"),
            ParityClosingGateLint.parseAcceptanceLines(body),
        )
    }

    // ── isSwiftAcceptance / filename helpers ─────────────────────────────────

    @Test
    fun `detects Swift acceptance by dot-swift`() {
        assertTrue(ParityClosingGateLint.isSwiftAcceptance("FooTests.swift#testBar"))
        assertFalse(ParityClosingGateLint.isSwiftAcceptance("FooTest#testBar"))
    }

    @Test
    fun `extracts Swift filename with extension`() {
        assertEquals(
            "FooTests.swift",
            ParityClosingGateLint.swiftFilenameFromAcceptance("FooTests.swift#testBar"),
        )
        assertEquals(
            "FooTests.swift",
            ParityClosingGateLint.swiftFilenameFromAcceptance("FooTests.swift"),
        )
    }

    @Test
    fun `extracts function name after hash`() {
        assertEquals("testBar", ParityClosingGateLint.functionNameFromAcceptance("FooTest#testBar"))
        assertEquals(
            "testBar",
            ParityClosingGateLint.functionNameFromAcceptance("com.riffle.foo.FooTest#testBar"),
        )
    }

    // ── findKotlinTestFiles ──────────────────────────────────────────────────

    private fun sourceMap(vararg entries: Pair<String, String>) = mapOf(*entries)

    private fun ktTest(name: String): String =
        "@org.junit.Test\nfun $name() {}"

    @Test
    fun `finds matching function in commonTest source set`() {
        val map = sourceMap(
            "core/foo/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomething"),
        )
        assertEquals(
            listOf("core/foo/src/commonTest/kotlin/FooTest.kt"),
            ParityClosingGateLint.findKotlinTestFiles("testSomething", map),
        )
    }

    @Test
    fun `finds matching function in iosTest source set`() {
        val map = sourceMap(
            "core/foo/src/iosTest/kotlin/FooTest.kt" to ktTest("testIos"),
        )
        assertEquals(
            listOf("core/foo/src/iosTest/kotlin/FooTest.kt"),
            ParityClosingGateLint.findKotlinTestFiles("testIos", map),
        )
    }

    @Test
    fun `ignores androidTest and jvmTest source sets`() {
        val map = sourceMap(
            "app/src/androidTest/kotlin/FooTest.kt" to ktTest("testAndroid"),
            "app/src/test/kotlin/FooTest.kt" to ktTest("testJvm"),
        )
        assertEquals(emptyList<String>(), ParityClosingGateLint.findKotlinTestFiles("testAndroid", map))
        assertEquals(emptyList<String>(), ParityClosingGateLint.findKotlinTestFiles("testJvm", map))
    }

    @Test
    fun `returns empty list when function not found anywhere`() {
        val map = sourceMap(
            "core/foo/src/commonTest/kotlin/FooTest.kt" to ktTest("testOther"),
        )
        assertEquals(emptyList<String>(), ParityClosingGateLint.findKotlinTestFiles("testMissing", map))
    }

    // ── moduleRootFromPath ───────────────────────────────────────────────────

    @Test
    fun `extracts module root from commonTest path`() {
        assertEquals(
            "core/common",
            ParityClosingGateLint.moduleRootFromPath("core/common/src/commonTest/kotlin/FooTest.kt"),
        )
    }

    @Test
    fun `extracts module root from iosTest path`() {
        assertEquals(
            "feature/reader",
            ParityClosingGateLint.moduleRootFromPath("feature/reader/src/iosTest/kotlin/ReaderTest.kt"),
        )
    }

    // ── isModuleCiWired ──────────────────────────────────────────────────────

    @Test
    fun `recognizes a wired module`() {
        val yml = ":core:common:iosSimulatorArm64Test \\\n"
        assertTrue(ParityClosingGateLint.isModuleCiWired("core/common", yml))
    }

    @Test
    fun `rejects an unwired module`() {
        val yml = ":core:models:iosSimulatorArm64Test \\\n"
        assertFalse(ParityClosingGateLint.isModuleCiWired("feature/settings", yml))
    }

    // ── isSwiftFilePbxprojMember ─────────────────────────────────────────────

    @Test
    fun `finds a Swift file referenced in pbxproj`() {
        val pbxproj = """
            A1C0DE010001 /* FooTests.swift in Sources */ = {isa = PBXBuildFile; fileRef = A1C0DE010002 /* FooTests.swift */; };
        """.trimIndent()
        assertTrue(ParityClosingGateLint.isSwiftFilePbxprojMember("FooTests.swift", pbxproj))
    }

    @Test
    fun `rejects a Swift file absent from pbxproj`() {
        val pbxproj = """
            A1C0DE010001 /* OtherTests.swift in Sources */ = {isa = PBXBuildFile; fileRef = A1C0DE010002 /* OtherTests.swift */; };
        """.trimIndent()
        assertFalse(ParityClosingGateLint.isSwiftFilePbxprojMember("FooTests.swift", pbxproj))
    }

    // ── checkAcceptanceTests — the four gate scenarios ───────────────────────

    private val WIRED_YML = ":core:foo:iosSimulatorArm64Test \\\n"
    private val MEMBER_PBXPROJ =
        "A100 /* FooTests.swift in Sources */ = {isa = PBXBuildFile; fileRef = A200 /* FooTests.swift */; };"

    @Test
    fun `existing wired Kotlin test passes`() {
        val issueBody = "- Acceptance: `FooTest#testSomething`"
        val sourceMap = sourceMap(
            "core/foo/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomething"),
        )
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = sourceMap,
            iosYmlContent = WIRED_YML,
            pbxprojContent = "",
        )
        assertEquals(emptyList<ParityClosingGateLint.Violation>(), violations)
    }

    @Test
    fun `missing Kotlin test produces MISSING_TEST violation`() {
        val issueBody = "- Acceptance: `FooTest#testMissing`"
        val sourceMap = sourceMap(
            "core/foo/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomethingElse"),
        )
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = sourceMap,
            iosYmlContent = WIRED_YML,
            pbxprojContent = "",
        )
        assertEquals(1, violations.size)
        assertEquals(ParityClosingGateLint.ViolationKind.MISSING_TEST, violations[0].kind)
    }

    @Test
    fun `Kotlin test in unwired module produces UNWIRED_MODULE violation`() {
        val issueBody = "- Acceptance: `FooTest#testSomething`"
        val sourceMap = sourceMap(
            "feature/settings/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomething"),
        )
        val unwiredYml = ":core:common:iosSimulatorArm64Test \\\n"
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = sourceMap,
            iosYmlContent = unwiredYml,
            pbxprojContent = "",
        )
        assertEquals(1, violations.size)
        assertEquals(ParityClosingGateLint.ViolationKind.UNWIRED_MODULE, violations[0].kind)
    }

    @Test
    fun `Swift test file absent from pbxproj produces SWIFT_NOT_IN_PBXPROJ violation`() {
        val issueBody = "- Acceptance: `FooTests.swift#testBar`"
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = emptyMap(),
            iosYmlContent = "",
            pbxprojContent = "/* OtherTests.swift */",
        )
        assertEquals(1, violations.size)
        assertEquals(ParityClosingGateLint.ViolationKind.SWIFT_NOT_IN_PBXPROJ, violations[0].kind)
    }

    @Test
    fun `Swift test file present in pbxproj passes`() {
        val issueBody = "- Acceptance: `FooTests.swift#testBar`"
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = emptyMap(),
            iosYmlContent = "",
            pbxprojContent = MEMBER_PBXPROJ,
        )
        assertEquals(emptyList<ParityClosingGateLint.Violation>(), violations)
    }

    // ── checkOpenSubIssues ───────────────────────────────────────────────────

    @Test
    fun `no open sub-issues returns null`() {
        val result = ParityClosingGateLint.checkOpenSubIssues(issueNumber = 42, openSubIssueNumbers = emptyList())
        assertEquals(null, result)
    }

    @Test
    fun `open sub-issues produce OPEN_SUB_ISSUES violation`() {
        val result = ParityClosingGateLint.checkOpenSubIssues(
            issueNumber = 42,
            openSubIssueNumbers = listOf(101, 102),
        )
        assertEquals(ParityClosingGateLint.ViolationKind.OPEN_SUB_ISSUES, result?.kind)
        assertEquals(42, result?.issueNumber)
        assertTrue(result?.detail?.contains("#101") == true)
        assertTrue(result?.detail?.contains("#102") == true)
        assertTrue(result?.detail?.contains("Part of #42") == true)
    }

    @Test
    fun `single open sub-issue blocks close`() {
        val result = ParityClosingGateLint.checkOpenSubIssues(
            issueNumber = 200,
            openSubIssueNumbers = listOf(201),
        )
        assertEquals(ParityClosingGateLint.ViolationKind.OPEN_SUB_ISSUES, result?.kind)
    }

    // ── checkAcceptanceTests — the four gate scenarios ───────────────────────

    @Test
    fun `issue without Acceptance lines is vacuously clean`() {
        val issueBody = """
            Android evidence: FooTest.kt:42
            iOS entry point: Settings screen
        """.trimIndent()
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 99,
            issueBody = issueBody,
            testSourceMap = emptyMap(),
            iosYmlContent = "",
            pbxprojContent = "",
        )
        assertEquals(emptyList<ParityClosingGateLint.Violation>(), violations)
    }

    @Test
    fun `test present in at least one wired module passes even if also in unwired module`() {
        val issueBody = "- Acceptance: `FooTest#testSomething`"
        val sourceMap = sourceMap(
            "core/foo/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomething"),
            "feature/unwired/src/commonTest/kotlin/FooTest.kt" to ktTest("testSomething"),
        )
        val yml = ":core:foo:iosSimulatorArm64Test \\\n"
        val violations = ParityClosingGateLint.checkAcceptanceTests(
            issueNumber = 42,
            issueBody = issueBody,
            testSourceMap = sourceMap,
            iosYmlContent = yml,
            pbxprojContent = "",
        )
        assertEquals(emptyList<ParityClosingGateLint.Violation>(), violations)
    }
}
