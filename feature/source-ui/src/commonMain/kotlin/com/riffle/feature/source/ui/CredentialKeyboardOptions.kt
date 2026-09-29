package com.riffle.feature.source.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

// The iOS keyboard defaults to sentence capitalisation + autocorrect, which silently turns
// "test" → "Test" and makes valid credentials return 401. Both guards are applied to every
// credential field (URL, username, password) so the fix is consistent and testable.
//
// KeyboardType.Password is intentionally avoided for the password field: on iOS it sets
// isSecureTextEntry=true, which overrides UITextAutocapitalizationType.none and auto-capitalises
// the first character regardless of the capitalization option. PasswordVisualTransformation in
// AddSourceScreen provides the visual masking instead.

internal val credentialKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Text,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

internal val passwordKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Text,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)
