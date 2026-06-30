package com.riffle.app.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {

    @Test
    fun `map transforms Success`() {
        val state: UiState<Int> = UiState.Success(2)

        val result = state.map { it * 3 }

        assertEquals(UiState.Success(6), result)
    }

    @Test
    fun `map preserves Loading`() {
        val state: UiState<Int> = UiState.Loading

        val result = state.map { it * 3 }

        assertSame(UiState.Loading, result)
    }

    @Test
    fun `map preserves Error with all fields`() {
        val retry: () -> Unit = {}
        val state: UiState<Int> = UiState.Error("boom", code = "X1", retry = retry)

        val result = state.map { it.toString() }

        assertTrue(result is UiState.Error)
        result as UiState.Error
        assertEquals("boom", result.message)
        assertEquals("X1", result.code)
        assertSame(retry, result.retry)
    }

    @Test
    fun `valueOrNull returns the value on Success`() {
        val state: UiState<String> = UiState.Success("hi")

        assertEquals("hi", state.valueOrNull())
    }

    @Test
    fun `valueOrNull is null on Loading`() {
        val state: UiState<String> = UiState.Loading

        assertNull(state.valueOrNull())
    }

    @Test
    fun `valueOrNull is null on Error`() {
        val state: UiState<String> = UiState.Error("oops")

        assertNull(state.valueOrNull())
    }

    @Test
    fun `Error defaults code and retry to null`() {
        val error = UiState.Error("oops")

        assertNull(error.code)
        assertNull(error.retry)
    }
}
