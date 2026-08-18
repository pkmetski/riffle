package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun DeveloperOptionsSection(onSaveGithubPat: (String) -> Unit) {
    HorizontalDivider()
    SettingsSectionHeader("Developer options")
    var pat by remember { mutableStateOf("") }
    ListItem(
        headlineContent = {
            OutlinedTextField(
                value = pat,
                onValueChange = { pat = it },
                label = { Text("GitHub PAT (public_repo scope)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        trailingContent = {
            TextButton(onClick = { onSaveGithubPat(pat) }) { Text("Save") }
        },
    )
}
