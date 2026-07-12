package com.riffle.app.feature.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.app.BuildConfig
import com.riffle.app.feature.annotationsync.AnnotationSyncKind
import com.riffle.app.feature.annotationsync.deriveAnnotationSyncKind
import com.riffle.core.data.AnnotationSyncStatusStore
import com.riffle.core.data.CycleOutcome
import com.riffle.core.data.ReadaloudMatchingService
import com.riffle.core.database.AnnotationDao
import com.riffle.core.data.StorytellerReadaloudSyncer
import com.riffle.core.data.TestConnectionResult
import com.riffle.core.data.WebDavAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.Clock
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * Backend kind selectable in [AddSourceScreen]. Distinct from [ServerType] because WebDAV is
 * not a content "server" in the domain sense — it just shares the same URL+username+password
 * shape — and we want a single screen for adding any of them.
 */
enum class AddSourceBackend { AUDIOBOOKSHELF, STORYTELLER, WEBDAV, KOMGA }

@HiltViewModel
class AddSourceViewModel @Inject constructor(
    private val repository: SourceRepository,
    private val webdavConfigStore: AnnotationSyncConfigStore,
    private val webdavTargetFactory: WebDavAnnotationSyncTargetFactory,
    private val webdavStatusStore: AnnotationSyncStatusStore,
    private val sweepEnqueuer: AnnotationSweepEnqueuer,
    private val storytellerSyncer: StorytellerReadaloudSyncer,
    private val readaloudMatcher: ReadaloudMatchingService,
    private val tokenStorage: TokenStorage,
    private val clock: Clock,
    private val annotationDao: AnnotationDao,
    @Named(WEBDAV_BANNER_TICKER) private val bannerTicker: Flow<Unit>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    var backend by mutableStateOf(AddSourceBackend.AUDIOBOOKSHELF)
        private set
    /** Source id when editing an existing Storyteller/ABS server; null for add or for WebDAV. */
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
        annotationDao.observePendingBookCountAcrossAll(),
        bannerTicker,
    ) { config, outcome, lastSuccessAtMs, pendingCount, _ ->
        config?.let { webdavBanner(it, outcome, pendingCount, lastSuccessAtMs) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var insecureWarning by mutableStateOf<InsecureConnectionType?>(null)

    private val _navigateToSelectLibraries = Channel<PendingSource>(Channel.CONFLATED)
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
            AddSourceBackend.AUDIOBOOKSHELF, AddSourceBackend.STORYTELLER, AddSourceBackend.KOMGA -> {
                val id = editingServerId ?: return
                val server = repository.getById(id) ?: return
                applyUrl(server.url.value)
                username = server.username
                password = tokenStorage.getPassword(id).orEmpty()
            }
            AddSourceBackend.WEBDAV -> {
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
            AddSourceBackend.AUDIOBOOKSHELF, AddSourceBackend.STORYTELLER, AddSourceBackend.KOMGA -> connectServer()
            AddSourceBackend.WEBDAV -> connectWebdav()
        }
    }

    private fun connectServer() {
        val serverUrl = SourceUrl.parse(url.trim())
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
                    error = "Source returned HTTP ${result.code}."
            }
            isLoading = false
        }
    }

    fun onInsecureWarningAccepted() {
        insecureWarning = null
        val serverUrl = SourceUrl.parse(url.trim()) ?: return
        doAuthenticate(serverUrl, insecureAllowed = true)
    }

    fun onInsecureWarningDismissed() {
        insecureWarning = null
    }

    private fun doAuthenticate(serverUrl: SourceUrl, insecureAllowed: Boolean) {
        val serverType = backend.toServerType() ?: return
        val sourceType = backend.toSourceType()
        viewModelScope.launch {
            isLoading = true
            when (val result = repository.authenticate(serverUrl, username, password, insecureAllowed, serverType, sourceType)) {
                is AuthenticateResult.Success -> {
                    val pending = result.pending
                    if (pending.libraries.size <= 1) {
                        // Only now that auth has succeeded is it safe to remove the existing
                        // row — otherwise a failed edit attempt (wrong password, network blip)
                        // would destroy the user's server without replacement.
                        editingServerId?.let { repository.remove(it) }
                        when (val c = repository.commit(pending, hiddenLibraryIds = emptySet())) {
                            is CommitSourceResult.Success -> {
                                if (serverType == ServerType.STORYTELLER_SERVICE) {
                                    runCatching { storytellerSyncer.syncStale() }
                                    runCatching { readaloudMatcher.reconcileLinks() }
                                }
                                _navigateHome.send(Unit)
                            }
                            is CommitSourceResult.Failure ->
                                error = "Couldn't save source: ${c.cause.message}"
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
                AddSourceBackend.AUDIOBOOKSHELF, AddSourceBackend.STORYTELLER, AddSourceBackend.KOMGA ->
                    editingServerId?.let { repository.remove(it) }
                AddSourceBackend.WEBDAV -> webdavConfigStore.clear()
            }
            _navigateHome.send(Unit)
        }
    }

    private fun webdavBanner(
        config: AnnotationSyncConfig,
        outcome: CycleOutcome,
        pendingBookCount: Int,
        lastSuccessAtMs: Long?,
    ): WebdavBanner {
        // Kind comes from the shared derivation so this banner cannot disagree with the Settings
        // "WebDAV" row (see [AnnotationSyncKind]). Prescription copy stays local because the
        // banner surfaces action guidance the Settings row does not.
        val bannerKind = when (deriveAnnotationSyncKind(config, outcome, pendingBookCount)) {
            AnnotationSyncKind.Synced -> WebdavBannerKind.Synced
            AnnotationSyncKind.Pending -> WebdavBannerKind.Pending
            AnnotationSyncKind.Error -> WebdavBannerKind.Error
            // config is non-null on this code path (banner is hidden otherwise), so Local is
            // unreachable — fall back to Pending defensively.
            AnnotationSyncKind.Local -> WebdavBannerKind.Pending
        }
        val prescription = when {
            outcome is CycleOutcome.Failed.Auth ->
                "Authentication failed — your credentials may have expired. Re-enter them below; sync will retry once saved."
            outcome is CycleOutcome.Failed.Tls ->
                "TLS error — the server's certificate could not be verified. Update the URL below; sync will retry once saved."
            outcome is CycleOutcome.Failed.Server ->
                "Source returned HTTP ${outcome.code}. Will retry automatically."
            outcome is CycleOutcome.Failed.Unknown ->
                "Sync failed. Will retry automatically."
            outcome is CycleOutcome.Failed.Network ->
                "Couldn't reach the server. Will retry automatically when connectivity returns."
            outcome is CycleOutcome.Success && pendingBookCount > 0 ->
                "$pendingBookCount book(s) pending · will sync shortly."
            outcome is CycleOutcome.NeverRun && pendingBookCount > 0 ->
                "$pendingBookCount book(s) pending · waiting for first sync."
            else -> null
        }
        val host = runCatching { java.net.URI(config.baseUrl).host ?: config.baseUrl }
            .getOrElse { config.baseUrl }
        return WebdavBanner(
            kind = bannerKind,
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
        val elapsedSec = (clock.nowMs() - lastSuccessAtMs) / 1_000L
        return when {
            elapsedSec < 60 -> "just now"
            elapsedSec < 3_600 -> "${elapsedSec / 60} min ago"
            elapsedSec < 86_400 -> "${elapsedSec / 3_600} h ago"
            else -> "${elapsedSec / 86_400} d ago"
        }
    }

    companion object {
        /**
         * Hilt qualifier for the [webdavBanner]'s wall-clock ticker. See [WebdavBannerTickerModule]
         * for the production ticker (once a minute), and [AddSourceViewModelTest] for the test
         * override.
         */
        const val WEBDAV_BANNER_TICKER = "webdavBannerTicker"
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

private fun parseBackend(raw: String?): AddSourceBackend = when (raw?.lowercase()) {
    "storyteller" -> AddSourceBackend.STORYTELLER
    "webdav" -> AddSourceBackend.WEBDAV
    "komga" -> AddSourceBackend.KOMGA
    else -> AddSourceBackend.AUDIOBOOKSHELF
}

private fun AddSourceBackend.toServerType(): ServerType? = when (this) {
    AddSourceBackend.AUDIOBOOKSHELF -> ServerType.AUDIOBOOKSHELF
    AddSourceBackend.STORYTELLER -> ServerType.STORYTELLER_SERVICE
    // Komga has no ServerType subtype; ServerEntity.serverType stays "AUDIOBOOKSHELF" as filler
    // (it's only meaningful for SourceType.ABS which owns the AUDIOBOOKSHELF/STORYTELLER split).
    AddSourceBackend.KOMGA -> ServerType.AUDIOBOOKSHELF
    AddSourceBackend.WEBDAV -> null
}

private fun AddSourceBackend.toSourceType(): SourceType = when (this) {
    AddSourceBackend.AUDIOBOOKSHELF, AddSourceBackend.STORYTELLER -> SourceType.ABS
    AddSourceBackend.KOMGA -> SourceType.KOMGA
    // WebDAV never reaches SourceType-keyed code paths (its own connectWebdav branch).
    AddSourceBackend.WEBDAV -> SourceType.ABS
}
