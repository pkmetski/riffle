package com.riffle.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject

@Composable
fun AddAbsSourceScreen(
    onSourceAdded: () -> Unit,
    viewModel: AddAbsSourceViewModel = koinInject(),
) {
    LaunchedEffect(Unit) {
        viewModel.sourceAdded.collect { onSourceAdded() }
    }

    Column(
        modifier = Modifier.padding(24.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText("Add Audiobookshelf Server")
        LabeledField(label = "Server URL", value = viewModel.url, onValueChange = { viewModel.url = it })
        LabeledField(label = "Username", value = viewModel.username, onValueChange = { viewModel.username = it })
        LabeledField(
            label = "Password",
            value = viewModel.password,
            onValueChange = { viewModel.password = it },
            visualTransformation = PasswordVisualTransformation(),
        )
        viewModel.error?.let { BasicText(it) }
        if (viewModel.isLoading) {
            BasicText("Connecting…")
        } else {
            val canConnect = viewModel.url.isNotBlank() && viewModel.username.isNotBlank() && viewModel.password.isNotBlank()
            BasicText(
                "Connect",
                modifier = Modifier
                    .border(1.dp, if (canConnect) Color.Black else Color.Gray)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(if (canConnect) Modifier.clickable { viewModel.onConnect() } else Modifier),
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Gray)
                .padding(8.dp),
        )
    }
}
