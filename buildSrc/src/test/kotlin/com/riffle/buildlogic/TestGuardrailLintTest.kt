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
}
