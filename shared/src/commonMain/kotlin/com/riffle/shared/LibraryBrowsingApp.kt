package com.riffle.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun LibraryBrowsingApp() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText("Riffle — library browsing coming soon")
    }
}
