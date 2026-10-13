package com.riffle.shared.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.core.catalog.oreilly.parseOrmJwtFromCookieHeader
import com.riffle.core.domain.OReillyWebSourceDescriptor
import com.riffle.core.domain.SourceRepository
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.source.ui.oreilly.OReillyLoginCommonViewModel
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_back
import com.riffle.shared.generated.resources.ui_oreilly_sign_in_failed
import com.riffle.shared.generated.resources.ui_oreilly_sign_in_title
import com.riffle.shared.generated.resources.ui_oreilly_signing_in
import com.riffle.shared.generated.resources.ui_oreilly_signing_in_sub
import com.riffle.shared.generated.resources.ui_try_again
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import platform.CoreGraphics.CGRect
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosOReillyLoginScreen(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sourceRepository = koinInject<SourceRepository>()
    val viewModel = remember { OReillyLoginCommonViewModel(sourceRepository, scope) }
    val loginState by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.done.collect { onDone() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_oreilly_sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            RiffleIcons.ArrowBack,
                            contentDescription = stringResource(Res.string.ui_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            OReillyLoginWebView(
                modifier = Modifier.fillMaxSize(),
                onSessionCookie = { cookieHeader ->
                    iosOReillySessionStore.update(cookieHeader)
                    viewModel.onTokenHarvested(cookieHeader)
                },
            )
            when (loginState) {
                OReillyLoginCommonViewModel.State.Installing -> SigningInOverlay()
                OReillyLoginCommonViewModel.State.Error -> ErrorOverlay(onRetry = viewModel::retry)
                OReillyLoginCommonViewModel.State.SigningIn -> Unit
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun OReillyLoginWebView(
    onSessionCookie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cookieCallback by rememberUpdatedState(onSessionCookie)
    // Keep a strong Kotlin reference — WKWebView.navigationDelegate is weak on the ObjC side
    // and would be garbage-collected without this hold.
    val delegate = remember { OReillyNavigationDelegate { header -> cookieCallback(header) } }

    UIKitView(
        factory = {
            WKWebView(
                frame = cValue<CGRect>(),
                configuration = WKWebViewConfiguration(),
            ).apply {
                navigationDelegate = delegate
                val url = NSURL.URLWithString(OReillyWebSourceDescriptor.OREILLY_LOGIN_URL)!!
                loadRequest(NSURLRequest(url))
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class OReillyNavigationDelegate(
    private val onCookieHarvested: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    // Prevent harvesting on every page load after auth completes.
    private var harvested = false

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (harvested) return
        WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { rawCookies ->
            if (harvested) return@getAllCookies
            @Suppress("UNCHECKED_CAST")
            val cookies = rawCookies as? List<NSHTTPCookie> ?: return@getAllCookies
            val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            if (parseOrmJwtFromCookieHeader(header) != null) {
                harvested = true
                onCookieHarvested(header)
            }
        }
    }
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
                stringResource(Res.string.ui_oreilly_signing_in),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                stringResource(Res.string.ui_oreilly_signing_in_sub),
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
                stringResource(Res.string.ui_oreilly_sign_in_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(Res.string.ui_try_again))
            }
        }
    }
}
