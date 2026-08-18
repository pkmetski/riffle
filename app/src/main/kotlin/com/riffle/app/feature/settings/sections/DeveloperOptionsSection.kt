package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun DeveloperOptionsSection(
    onSaveGithubPat: (String) -> Unit,
    onPatSaved: () -> Unit = {},
) {
    HorizontalDivider()
    SettingsSectionHeader("Developer options")
    var pat by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            label = { Text("GitHub PAT (public_repo scope)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    onSaveGithubPat(pat)
                    pat = ""
                    onPatSaved()
                },
                enabled = pat.isNotBlank(),
            ) { Text("Save") }
        }
    }
}
