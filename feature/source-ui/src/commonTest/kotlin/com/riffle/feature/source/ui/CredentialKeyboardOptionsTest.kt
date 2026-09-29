package com.riffle.feature.source.ui

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CredentialKeyboardOptionsTest {

    @Test
    fun passwordKeyboardOptionsDoesNotUsePasswordKeyboardType() {
        // KeyboardType.Password sets isSecureTextEntry=true on iOS, which overrides
        // UITextAutocapitalizationType.none and auto-capitalises the first character.
        // This silently turns "test" → "Test" and causes valid credentials to 401.
        assertNotEquals(KeyboardType.Password, passwordKeyboardOptions.keyboardType)
    }

    @Test
    fun passwordKeyboardOptionsDisablesCapitalization() {
        assertEquals(KeyboardCapitalization.None, passwordKeyboardOptions.capitalization)
    }

    @Test
    fun credentialKeyboardOptionsDisablesCapitalization() {
        assertEquals(KeyboardCapitalization.None, credentialKeyboardOptions.capitalization)
    }

    @Test
    fun credentialKeyboardOptionsDoesNotUsePasswordKeyboardType() {
        assertNotEquals(KeyboardType.Password, credentialKeyboardOptions.keyboardType)
    }
}
