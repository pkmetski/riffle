package com.riffle.app.feature.source.oreilly

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.riffle.app.R
import com.riffle.core.domain.OReillyWebSourceDescriptor
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OReillyLoginScreen(
    windowSizeClass: WindowSizeClass,
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: OReillyLoginViewModel = koinViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.done.collect { onDone() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ui_oreilly_sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ui_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            OReillyLoginWebView(
                onSessionCookie = { cookieHeader ->
                    viewModel.onTokenHarvested(cookieHeader, accountEmail = null)
                },
                modifier = Modifier.fillMaxSize(),
            )
            when (viewModel.state) {
                OReillyLoginViewModel.State.Installing ->
                    SigningInOverlay()
                OReillyLoginViewModel.State.Error ->
                    ErrorOverlay(onRetry = viewModel::retry)
                OReillyLoginViewModel.State.SigningIn -> Unit
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OReillyLoginWebView(
    onSessionCookie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            WebView(ctx).apply {
                cookies.setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Auth is complete once `orm-jwt` appears. But O'Reilly's API needs the
                        // WHOLE session (orm-jwt + session cookies) — a partial cookie set reads as
                        // anonymous and 404s subscriber content — so harvest the entire cookie
                        // header and hand that to the catalog, using orm-jwt only as the signal.
                        val header = cookies.getCookie(OReillyWebSourceDescriptor.OREILLY_BASE_URL)
                        if (!header.isNullOrBlank() && parseOrmJwtFromCookieHeader(header) != null) {
                            cookies.flush()
                            onSessionCookie(header)
                        }
                    }
                }
                loadUrl(OReillyWebSourceDescriptor.OREILLY_LOGIN_URL)
            }
        },
    )
}

@Composable
private fun SigningInOverlay() {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.ui_oreilly_signing_in),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                stringResource(R.string.ui_oreilly_signing_in_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorOverlay(onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.ui_oreilly_sign_in_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.ui_try_again))
            }
        }
    }
}

/**
 * Extracts the `orm-jwt` value from a `CookieManager.getCookie` header string of the form
 * `"a=1; orm-jwt=<jwt>; b=2"`. Returns null when absent or blank. Top-level + internal so it's
 * unit-testable without a WebView.
 */
internal fun parseOrmJwtFromCookieHeader(header: String?): String? {
    if (header.isNullOrBlank()) return null
    for (part in header.split(';')) {
        val trimmed = part.trim()
        if (trimmed.startsWith("orm-jwt=")) {
            val value = trimmed.removePrefix("orm-jwt=").trim()
            if (value.isNotBlank()) return value
        }
    }
    return null
}
