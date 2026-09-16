package com.riffle.feature.source.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

// The iOS keyboard defaults to sentence capitalisation + autocorrect, which silently turns
// "test2" → "Test2" and makes valid credentials return 401.  Both guards are applied to every
// credential field (URL, username, password) so the fix is consistent and testable.

internal val credentialKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Text,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

internal val passwordKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)
