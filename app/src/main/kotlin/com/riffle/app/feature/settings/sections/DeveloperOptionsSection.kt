package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun DeveloperOptionsSection(onOpen: () -> Unit) {
    SettingsSectionHeader("Developer options")
    ListItem(
        headlineContent = { Text("Developer options") },
        trailingContent = { DrillInChevron() },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

/** PAT input field used inside the Developer Options screen. */
@Composable
internal fun GithubPatField(
    currentPat: String,
    onSaveGithubPat: (String) -> Unit,
) {
    var pat by remember { mutableStateOf(currentPat) }
    var isSaved by remember { mutableStateOf(currentPat.isNotEmpty()) }

    LaunchedEffect(currentPat) {
        if (currentPat.isNotEmpty() && pat.isEmpty()) {
            pat = currentPat
            isSaved = true
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it; isSaved = false },
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
                onClick = { onSaveGithubPat(pat); isSaved = true },
                enabled = pat.isNotBlank() && !isSaved,
            ) { Text("Save") }
        }
    }
}
