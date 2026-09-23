package com.riffle.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestGuardrailLintTest {

    // ── extractTestNames ──────────────────────────────────────────────────

    @Test
    fun `extracts plain and backticked test names`() {
        val source = """
            class FooTest {
                @Test
                fun plainName() {}

                @Test
                fun `backticked name with spaces`() {}
            }
        """.trimIndent()
        assertEquals(
            setOf("plainName", "backticked name with spaces"),
            TestGuardrailLint.extractTestNames(source),
        )
    }

    @Test
    fun `tolerates annotations and modifiers between Test and fun`() {
        val source = """
            @Test
            @TabletLayout
            internal fun annotatedTest() {}
        """.trimIndent()
        assertEquals(setOf("annotatedTest"), TestGuardrailLint.extractTestNames(source))
    }

    @Test
    fun `ignores non-test functions and TestOnly annotations`() {
        val source = """
            @TestOnly
            fun helper() {}

            fun alsoHelper() {}

            @Test
            fun realTest() {}
        """.trimIndent()
        assertEquals(setOf("realTest"), TestGuardrailLint.extractTestNames(source))
    }

    @Test
    fun `matches fully qualified Test annotation`() {
        val source = """
            @org.junit.Test
            fun qualified() {}
        """.trimIndent()
        assertEquals(setOf("qualified"), TestGuardrailLint.extractTestNames(source))
    }

    // ── isTestSourceFile ──────────────────────────────────────────────────

    @Test
    fun `recognizes test source sets`() {
        assertTrue(TestGuardrailLint.isTestSourceFile("app/src/test/kotlin/FooTest.kt"))
        assertTrue(TestGuardrailLint.isTestSourceFile("app/src/androidTest/kotlin/FooTest.kt"))
        assertTrue(TestGuardrailLint.isTestSourceFile("core/database/src/androidDeviceTest/kotlin/FooTest.kt"))
        assertTrue(TestGuardrailLint.isTestSourceFile("core/domain/src/jvmTest/kotlin/FooTest.kt"))
        assertFalse(TestGuardrailLint.isTestSourceFile("app/src/main/kotlin/Foo.kt"))
        assertFalse(TestGuardrailLint.isTestSourceFile("app/src/test/resources/fixture.json"))
    }

    // ── parseDeclaredRemovals ─────────────────────────────────────────────

    @Test
    fun `parses Removed-test trailers with and without backticks`() {
        val log = """
            fix(reader): collapse sections

            The old claim no longer holds because the user asked for X.

            Removed-test: `old behavior is pinned`
            Removed-test: plainOldTest
        """.trimIndent()
        assertEquals(
            setOf("old behavior is pinned", "plainOldTest"),
            TestGuardrailLint.parseDeclaredRemovals(log),
        )
    }

    // ── findUndeclaredRemovals ────────────────────────────────────────────

    @Test
    fun `renamed test is flagged under its old name`() {
        // The #656 motion: "subchapters are NOT promoted" renamed to assert the opposite.
        val old = mapOf("FooTest.kt" to setOf("subchapters are NOT promoted"))
        val new = mapOf("FooTest.kt" to setOf("subchapters are promoted"))
        assertEquals(
            listOf(TestGuardrailLint.RemovedTest("FooTest.kt", "subchapters are NOT promoted")),
            TestGuardrailLint.findUndeclaredRemovals(old, new, declared = emptySet()),
        )
    }

    @Test
    fun `declared removal is not flagged`() {
        val old = mapOf("FooTest.kt" to setOf("retired claim"))
        val new = mapOf("FooTest.kt" to emptySet<String>())
        assertEquals(
            emptyList<TestGuardrailLint.RemovedTest>(),
            TestGuardrailLint.findUndeclaredRemovals(old, new, declared = setOf("retired claim")),
        )
    }

    @Test
    fun `test moved between files is not flagged`() {
        val old = mapOf("FooTest.kt" to setOf("stable claim"))
        val new = mapOf("BarTest.kt" to setOf("stable claim"))
        assertEquals(
            emptyList<TestGuardrailLint.RemovedTest>(),
            TestGuardrailLint.findUndeclaredRemovals(old, new, declared = emptySet()),
        )
    }

    @Test
    fun `deleted file flags every test it contained`() {
        val old = mapOf("GoneTest.kt" to setOf("claim a", "claim b"))
        val new = emptyMap<String, Set<String>>()
        assertEquals(
            listOf(
                TestGuardrailLint.RemovedTest("GoneTest.kt", "claim a"),
                TestGuardrailLint.RemovedTest("GoneTest.kt", "claim b"),
            ),
            TestGuardrailLint.findUndeclaredRemovals(old, new, declared = emptySet()),
        )
    }

    // ── checkParityMirror ─────────────────────────────────────────────────

    @Test
    fun `flags app-test-only file with no counterpart`() {
        val added = setOf("app/src/test/kotlin/com/riffle/app/FooTest.kt")
        val all = setOf("feature/bar/src/commonTest/kotlin/BarTest.kt")
        assertEquals(
            listOf(TestGuardrailLint.ParityViolation("app/src/test/kotlin/com/riffle/app/FooTest.kt", "FooTest")),
            TestGuardrailLint.checkParityMirror(added, all),
        )
    }

    @Test
    fun `accepts commonTest counterpart with same filename`() {
        val added = setOf("app/src/test/kotlin/com/riffle/app/FooTest.kt")
        val all = setOf("feature/foo/src/commonTest/kotlin/com/riffle/feature/foo/FooTest.kt")
        assertEquals(emptyList<TestGuardrailLint.ParityViolation>(), TestGuardrailLint.checkParityMirror(added, all))
    }

    @Test
    fun `accepts iOS XCTest counterpart with plural name`() {
        val added = setOf("app/src/test/kotlin/com/riffle/app/BarTest.kt")
        val all = setOf("iosApp/iosAppTests/BarTests.swift")
        assertEquals(emptyList<TestGuardrailLint.ParityViolation>(), TestGuardrailLint.checkParityMirror(added, all))
    }

    @Test
    fun `accepts declared parity-skip`() {
        val added = setOf("app/src/test/kotlin/com/riffle/app/FooTest.kt")
        val all = emptySet<String>()
        assertEquals(
            emptyList<TestGuardrailLint.ParityViolation>(),
            TestGuardrailLint.checkParityMirror(added, all, declared = setOf("FooTest")),
        )
    }

    @Test
    fun `ignores non-app-test files`() {
        val added = setOf("feature/x/src/commonTest/kotlin/XTest.kt")
        val all = emptySet<String>()
        assertEquals(emptyList<TestGuardrailLint.ParityViolation>(), TestGuardrailLint.checkParityMirror(added, all))
    }

    @Test
    fun `parseDeclaredParitySkips strips backticks`() {
        val log = "Parity-skip: `FooTest`\nParity-skip: BarTest"
        assertEquals(setOf("FooTest", "BarTest"), TestGuardrailLint.parseDeclaredParitySkips(log))
    }

    @Test
    fun `isCounterpartTestFile matches commonTest and iosTest kotlin files`() {
        assertTrue(TestGuardrailLint.isCounterpartTestFile("feature/x/src/commonTest/kotlin/XTest.kt"))
        assertTrue(TestGuardrailLint.isCounterpartTestFile("core/y/src/iosTest/kotlin/YTest.kt"))
        assertFalse(TestGuardrailLint.isCounterpartTestFile("app/src/test/kotlin/ZTest.kt"))
    }

    @Test
    fun `isCounterpartTestFile matches swift files in iosApp XCTest targets`() {
        assertTrue(TestGuardrailLint.isCounterpartTestFile("iosApp/iosAppTests/FooTests.swift"))
        assertTrue(TestGuardrailLint.isCounterpartTestFile("iosApp/iosAppUnitTests/BarTest.swift"))
        assertFalse(TestGuardrailLint.isCounterpartTestFile("iosApp/iosApp/SomeView.swift"))
    }

    // ── checkIosModuleTestParity ──────────────────────────────────────────

    @Test
    fun `flags jvmTest file with no commonTest counterpart in same module`() {
        val added = setOf("core/domain/src/jvmTest/kotlin/com/riffle/core/domain/FooTest.kt")
        val all = setOf("feature/reader/src/commonTest/kotlin/FooTest.kt") // different module
        assertEquals(
            listOf(TestGuardrailLint.ParityViolation("core/domain/src/jvmTest/kotlin/com/riffle/core/domain/FooTest.kt", "FooTest")),
            TestGuardrailLint.checkIosModuleTestParity(added, all),
        )
    }

    @Test
    fun `accepts commonTest counterpart with same filename in same module`() {
        val added = setOf("core/domain/src/jvmTest/kotlin/com/riffle/core/domain/FooTest.kt")
        val all = setOf("core/domain/src/commonTest/kotlin/com/riffle/core/domain/FooTest.kt")
        assertEquals(emptyList<TestGuardrailLint.ParityViolation>(), TestGuardrailLint.checkIosModuleTestParity(added, all))
    }

    @Test
    fun `flags androidHostTest file with no commonTest counterpart`() {
        val added = setOf("feature/source-ui/src/androidHostTest/kotlin/com/riffle/feature/source/ui/BarTest.kt")
        val all = emptySet<String>()
        assertEquals(
            listOf(TestGuardrailLint.ParityViolation("feature/source-ui/src/androidHostTest/kotlin/com/riffle/feature/source/ui/BarTest.kt", "BarTest")),
            TestGuardrailLint.checkIosModuleTestParity(added, all),
        )
    }

    @Test
    fun `accepts declared parity-skip for iOS module test`() {
        val added = setOf("core/net/src/jvmTest/kotlin/com/riffle/core/network/OkHttpTest.kt")
        val all = emptySet<String>()
        assertEquals(
            emptyList<TestGuardrailLint.ParityViolation>(),
            TestGuardrailLint.checkIosModuleTestParity(added, all, declared = setOf("OkHttpTest")),
        )
    }

    @Test
    fun `ignores app-src-test files in iOS module parity check`() {
        // app/src/test is handled by checkParityMirror, not checkIosModuleTestParity
        val added = setOf("app/src/test/kotlin/com/riffle/app/FooTest.kt")
        val all = emptySet<String>()
        assertEquals(emptyList<TestGuardrailLint.ParityViolation>(), TestGuardrailLint.checkIosModuleTestParity(added, all))
    }

    @Test
    fun `commonTest in a different module does not satisfy same-module requirement`() {
        val added = setOf("core/domain/src/jvmTest/kotlin/com/riffle/core/domain/BazTest.kt")
        // BazTest.kt exists in core/logging commonTest, not core/domain
        val all = setOf("core/logging/src/commonTest/kotlin/com/riffle/core/logging/BazTest.kt")
        assertEquals(
            listOf(TestGuardrailLint.ParityViolation("core/domain/src/jvmTest/kotlin/com/riffle/core/domain/BazTest.kt", "BazTest")),
            TestGuardrailLint.checkIosModuleTestParity(added, all),
        )
    }
}
