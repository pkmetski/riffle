package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.TestConnectionResult
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backend kind selectable in [AddServerScreen]. Distinct from [ServerType] because WebDAV is
 * not a content "server" in the domain sense — it just shares the same URL+username+password
 * shape — and we want a single screen for adding any of them.
 */
enum class AddServerBackend { AUDIOBOOKSHELF, STORYTELLER, WEBDAV }

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val webdavConfigStore: AnnotationSyncConfigStore,
    private val webdavTargetFactory: WebDavAnnotationSyncTargetFactory,
    private val webdavStatusStore: AnnotationSyncStatusStore,
    private val sweepEnqueuer: AnnotationSweepEnqueuer,
    private val storytellerSyncer: StorytellerReadaloudSyncer,
    private val readaloudMatcher: ReadaloudMatchingService,
    private val tokenStorage: TokenStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    var backend by mutableStateOf(AddServerBackend.AUDIOBOOKSHELF)
        private set
    /** Server id when editing an existing Storyteller/ABS server; null for add or for WebDAV. */
    var editingServerId by mutableStateOf<String?>(null)
        private set
    /** True when the WebDAV backend already has a saved config (so we're editing, not adding). */
    var isEditingWebdav by mutableStateOf(false)
        private set
    val isEditing: Boolean get() = editingServerId != null || isEditingWebdav

    var scheme by mutableStateOf(initialScheme(BuildConfig.DEV_SERVER_URL))
        private set
    var host by mutableStateOf(stripScheme(BuildConfig.DEV_SERVER_URL))
        private set
    val url: String get() = scheme + host
    var username by mutableStateOf(BuildConfig.DEV_USERNAME)
    var password by mutableStateOf(BuildConfig.DEV_PASSWORD)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /**
     * Reactive banner state derived from the stored WebDAV config, the in-memory cycle outcome,
     * and the last-success timestamp — so when the periodic sweep flips Failed.Network → Success
     * in the background, the on-screen card updates without the screen being re-entered. Caller
     * (the WebDAV screen) is responsible for only rendering this on the WebDAV backend; it stays
     * non-null whenever a config exists, regardless of which backend the user is editing.
     */
    val webdavBanner: StateFlow<WebdavBanner?> = combine(
        webdavConfigStore.observe(),
        webdavStatusStore.lastCycleOutcome,
        webdavStatusStore.lastSuccessAtMs,
    ) { config, outcome, lastSuccessAtMs ->
        config?.let { webdavBanner(it, outcome, lastSuccessAtMs) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateToSelectLibraries = Channel<PendingServer>(Channel.CONFLATED)
    val navigateToSelectLibraries = _navigateToSelectLibraries.receiveAsFlow()

    private val _navigateHome = Channel<Unit>(Channel.CONFLATED)
    val navigateHome = _navigateHome.receiveAsFlow()

    init {
        backend = parseBackend(savedStateHandle.get<String>("type"))
        editingServerId = savedStateHandle.get<String>("editId")?.takeIf { it.isNotEmpty() }
        viewModelScope.launch { initFromRoute() }
    }

    private suspend fun initFromRoute() {
        when (backend) {
            AddServerBackend.AUDIOBOOKSHELF, AddServerBackend.STORYTELLER -> {
                val id = editingServerId ?: return
                val server = repository.getById(id) ?: return
                applyUrl(server.url.value)
                username = server.username
                password = tokenStorage.getPassword(id).orEmpty()
            }
            AddServerBackend.WEBDAV -> {
                val existing = webdavConfigStore.observe().first()
                if (existing != null) {
                    isEditingWebdav = true
                    applyUrl(existing.baseUrl)
                    username = existing.username
                    password = existing.password
                    // Banner content is driven by the reactive `webdavBanner` StateFlow above —
                    // intentionally no one-shot snapshot here, so the card refreshes when the
                    // background sweep flips the outcome.
                } else {
                    // Fresh WebDAV add — wipe ABS dev defaults so the form starts clean.
                    applyUrl("")
                    username = ""
                    password = ""
                }
            }
        }
    }

    private fun applyUrl(raw: String) {
        when {
            raw.startsWith("https://") -> { scheme = "https://"; host = raw.substring(8) }
            raw.startsWith("http://") -> { scheme = "http://"; host = raw.substring(7) }
            else -> { host = raw }
        }
    }

    fun updateScheme(value: String) {
        if (value == "http://" || value == "https://") scheme = value
    }

    fun updateHost(value: String) {
        val lower = value.lowercase()
        when {
            lower.startsWith("https://") -> { scheme = "https://"; host = value.substring(8) }
            lower.startsWith("http://") -> { scheme = "http://"; host = value.substring(7) }
            else -> host = value
        }
    }

    private fun initialScheme(devUrl: String): String =
        if (devUrl.startsWith("http://")) "http://" else "https://"

    private fun stripScheme(devUrl: String): String = when {
        devUrl.startsWith("https://") -> devUrl.substring(8)
        devUrl.startsWith("http://") -> devUrl.substring(7)
        else -> devUrl
    }

    fun onConnect() {
        error = null
        when (backend) {
            AddServerBackend.AUDIOBOOKSHELF, AddServerBackend.STORYTELLER -> connectServer()
            AddServerBackend.WEBDAV -> connectWebdav()
        }
    }

    private fun connectServer() {
        val serverUrl = ServerUrl.parse(url.trim())
        if (serverUrl == null) {
            error = "Enter a valid URL (e.g. https://abs.example.com)"
            return
        }
        if (serverUrl.value.startsWith("http://")) {
            insecureWarning = InsecureConnectionType.HTTP
            return
        }
        doAuthenticate(serverUrl, insecureAllowed = false)
    }

    private fun connectWebdav() {
        val config = AnnotationSyncConfig(url.trim(), username, password)
        val target = webdavTargetFactory.create(config)
        if (target == null) {
            error = "Could not parse URL — it should look like https://example.com/dav/path"
            return
        }
        viewModelScope.launch {
            isLoading = true
            val result = target.testConnection()
            when (result) {
                TestConnectionResult.Success -> {
                    webdavConfigStore.save(config)
                    sweepEnqueuer.enqueue()
                    _navigateHome.send(Unit)
                }
                TestConnectionResult.AuthFailed ->
                    error = "Authentication failed — check your username and password."
                is TestConnectionResult.InvalidUrl -> error = result.message
                is TestConnectionResult.NetworkError ->
                    error = "Couldn't reach the server: ${result.message}"
                is TestConnectionResult.TlsError ->
                    error = "TLS error: ${result.message}"
                is TestConnectionResult.ServerError ->
                    error = "Server returned HTTP ${result.code}."
            }
            isLoading = false
        }
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = ServerUrl.parse(url.trim()) ?: return
        doAuthenticate(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doAuthenticate(serverUrl: ServerUrl, insecureAllowed: Boolean) {
        val serverType = backend.toServerType() ?: return
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.authenticate(serverUrl, username, password, insecureAllowed, serverType)) {
                is AuthenticateResult.Success -> {
                    val pending = result.pending
                    if (pending.libraries.size <= 1) {
                        // Only now that auth has succeeded is it safe to remove the existing
                        // row — otherwise a failed edit attempt (wrong password, network blip)
                        // would destroy the user's server without replacement.
                        editingServerId?.let { repository.remove(it) }
                        when (val c = repository.commit(pending, hiddenLibraryIds = emptySet())) {
                            is CommitServerResult.Success -> {
                                if (serverType == ServerType.STORYTELLER) {
                                    runCatching { storytellerSyncer.syncStale() }
                                    runCatching { readaloudMatcher.reconcileLinks() }
                                }
                                _navigateHome.send(Unit)
                            }
                            is CommitServerResult.Failure ->
                                error = "Couldn't save server: ${c.cause.message}"
                        }
                    } else {
                        _navigateToSelectLibraries.send(pending)
                    }
                }
                is AuthenticateResult.WrongCredentials -> error = result.message
                is AuthenticateResult.NetworkError -> error = "Connection failed: ${result.cause.message}"
                is AuthenticateResult.LibraryFetchFailed ->
                    error = "Connected, but couldn't load libraries: ${result.cause.message}"
                is AuthenticateResult.InsecureConnection -> insecureWarning = result.type
            }
            isLoading = false
        }
    }

    /** Remove the current backend's stored configuration. Only meaningful in edit mode. */
    fun onRemove() {
        viewModelScope.launch {
            when (backend) {
                AddServerBackend.AUDIOBOOKSHELF, AddServerBackend.STORYTELLER ->
                    editingServerId?.let { repository.remove(it) }
                AddServerBackend.WEBDAV -> webdavConfigStore.clear()
            }
            _navigateHome.send(Unit)
        }
    }

    private fun webdavBanner(
        config: AnnotationSyncConfig,
        outcome: CycleOutcome,
        lastSuccessAtMs: Long?,
    ): WebdavBanner {
        val (kind, prescription) = when (outcome) {
            is CycleOutcome.NeverRun -> WebdavBannerKind.Pending to null
            is CycleOutcome.Success -> WebdavBannerKind.Synced to null
            is CycleOutcome.Failed.Auth -> WebdavBannerKind.Error to
                "Authentication failed — your credentials may have expired. Re-enter them below; sync will retry once saved."
            is CycleOutcome.Failed.Tls -> WebdavBannerKind.Error to
                "TLS error — the server's certificate could not be verified. Update the URL below; sync will retry once saved."
            is CycleOutcome.Failed.Server -> WebdavBannerKind.Error to
                "Server returned HTTP ${outcome.code}. Will retry automatically."
            is CycleOutcome.Failed.Network -> WebdavBannerKind.Pending to
                "Couldn't reach the server. Will retry automatically when connectivity returns."
            is CycleOutcome.Failed.Unknown -> WebdavBannerKind.Error to "Sync failed. Will retry automatically."
        }
        val host = runCatching { java.net.URI(config.baseUrl).host ?: config.baseUrl }
            .getOrElse { config.baseUrl }
        return WebdavBanner(
            kind = kind,
            host = host,
            baseUrl = config.baseUrl,
            username = config.username,
            lastSyncRelative = relativeSuccessTime(lastSuccessAtMs),
            prescription = prescription,
        )
    }

    /**
     * Renders "Last sync" as the elapsed time since the most recent *successful* push — not the
     * most recent attempt. A failing retry every few minutes would otherwise slide this forward to
     * "just now" while sync is in fact still broken.
     */
    private fun relativeSuccessTime(lastSuccessAtMs: Long?): String {
        if (lastSuccessAtMs == null) return "Never"
        val elapsedSec = (System.currentTimeMillis() - lastSuccessAtMs) / 1_000L
        return when {
            elapsedSec < 60 -> "just now"
            elapsedSec < 3_600 -> "${elapsedSec / 60} min ago"
            elapsedSec < 86_400 -> "${elapsedSec / 3_600} h ago"
            else -> "${elapsedSec / 86_400} d ago"
        }
    }
}

enum class WebdavBannerKind { Synced, Pending, Error }

data class WebdavBanner(
    val kind: WebdavBannerKind,
    val host: String,
    val baseUrl: String,
    val username: String,
    val lastSyncRelative: String,
    val prescription: String?,
)

private fun parseBackend(raw: String?): AddServerBackend = when (raw?.lowercase()) {
    "storyteller" -> AddServerBackend.STORYTELLER
    "webdav" -> AddServerBackend.WEBDAV
    else -> AddServerBackend.AUDIOBOOKSHELF
}

private fun AddServerBackend.toServerType(): ServerType? = when (this) {
    AddServerBackend.AUDIOBOOKSHELF -> ServerType.AUDIOBOOKSHELF
    AddServerBackend.STORYTELLER -> ServerType.STORYTELLER
    AddServerBackend.WEBDAV -> null
}
