package com.riffle.core.domain
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType

/**
 * Static per-source metadata that lets UI, install and drawer surfaces render a source without
 * an exhaustive `when(sourceType)` at every call site. One descriptor per [SourceType] lives as
 * a top-level Kotlin `object` in this file and is aggregated by [WebSourceDescriptors].
 *
 * Consumers have two shapes to pick from:
 *  * Composables and pure-Kotlin helpers use [WebSourceDescriptors.forType] directly — no
 *    injection needed, since every descriptor is a compile-time singleton.
 *  * Classes wanting Hilt-managed dependencies (e.g. the generic singleton-source installer)
 *    can inject the [WebSourceRegistry] bound in `WebSourceDescriptorModule`, which just wraps
 *    the same set.
 *
 * The descriptor holds display + capability + install data only. Icons live in `:app` because
 * `@DrawableRes Int` values are Android-only and can't be referenced from `:core:domain`;
 * `SourceIconResolver` keeps its exhaustive `when(sourceType)` so a new SourceType is a compile
 * error until its drawable ships.
 *
 * Behavioural specialisation belongs in the plugin's own module, not here.
 */
/**
 * How a [SourceType] surfaces the "To Read" list. Set explicitly on every [WebSourceDescriptor]
 * with **no default**: adding a new source is a compile error until the author decides which
 * bucket applies, so we never silently ship a source that hides the tab or writes only to the
 * device-local shadow when it should be talking to the server.
 */
enum class ToReadSupport {
    /**
     * The source's Catalog implements `PlaylistsCapability`. Riffle finds-or-creates a
     * server-side list named `"To Read"` (ABS Playlist, Komga readlist, etc.) and every add/
     * remove hits the server. Cross-device sync happens for free.
     */
    Synced,

    /**
     * The source has no server-side list concept — Riffle stores To Read in a per-device
     * DataStore ([com.riffle.core.data.LocalToReadStore]). The tab still renders, but nothing
     * syncs across devices. Correct for offline / catalog-only sources (Local Files, Chitanka,
     * Gutenberg) where there's no user account or per-user server state to write to.
     */
    LocalOnly,

    /**
     * The source explicitly does not surface a To Read tab at all. Rarely correct — pick this
     * only when the source's model makes a wishlist meaningless. There are no such sources in
     * Riffle today; the enum entry exists so future sources can opt out explicitly rather than
     * by omission.
     */
    Unsupported,
}

interface WebSourceDescriptor {
    val type: SourceType

    /** Human-visible name — drawer header, source-picker card, settings row. */
    val displayName: String

    /**
     * Explicit declaration of this source's To Read behaviour. No default: every new descriptor
     * MUST pick one of [ToReadSupport.Synced], [ToReadSupport.LocalOnly], or
     * [ToReadSupport.Unsupported] — a compile error otherwise. A runtime completeness test in
     * `:core:data` asserts the declaration matches the Catalog's actual capability set so the
     * two can't drift silently.
     */
    val toReadSupport: ToReadSupport

    /** Optional static subtitle. `null` when the subtitle depends on runtime data (host, version). */
    val subtitle: String? get() = null

    /** True when only one instance can be installed per device (Chitanka, Gutenberg, LocalFiles). */
    val isSingleton: Boolean get() = true

    /** True when the source authenticates as a user — drawer renders username/host. */
    val hasCredentials: Boolean get() = false

    /** True when the source has a distinct network host worth surfacing in UI. */
    val hasNetworkHost: Boolean get() = false

    /**
     * Optional host list surfaced by the Settings row's supporting text (e.g. `"chitanka.info ·
     * gramofonche.chitanka.info"`). `null` for sources that don't surface hosts in Settings.
     */
    val supportingHosts: String? get() = null

    /**
     * Route that the "Add source" picker navigates to when the user picks this source's card.
     * Adding a new source is one line in [WebSourceDescriptors.all] + one descriptor entry;
     * MainScreen doesn't need edits to route the picker.
     */
    val addRoute: String

    /**
     * Route prefix for the source's browse screen when the source is unbounded (Chitanka,
     * Gutenberg, …). `null` for sources whose libraries render through the shared
     * `library_items` screen (ABS, LocalFiles). The route is
     * `"$browseRoutePrefix/{libraryId}/{libraryName}"`.
     */
    val browseRoutePrefix: String? get() = null

    /**
     * Placeholder URL stamped into the `SourceEntity.url` column for zero-config sources whose
     * network endpoint is hardcoded in the catalog. `null` for user-configured sources (ABS)
     * that get their real URL from the Add-Source form. Must parse cleanly through
     * [SourceUrl.parse].
     */
    val urlPlaceholder: String? get() = null

    /**
     * Library rows to seed when the source is first installed (and self-heal on every re-install
     * pass). Empty for user-configured sources (ABS pulls its libraries live from the server).
     * The generic singleton installer upserts these on install; the ids must be stable so a
     * reinstall repairs the drawer without duplicating rows.
     */
    val defaultLibraries: List<DefaultLibrary> get() = emptyList()

    /**
     * Slot in the "Add source" picker. Lower renders higher. Default `Int.MAX_VALUE` places new
     * sources at the tail (registration order beyond that is unspecified).
     */
    val pickerOrder: Int get() = Int.MAX_VALUE

    /**
     * Marketing-shaped one-line blurb rendered under the picker card's title. Longer than
     * [subtitle] because the picker card has more real estate. Falls back to [displayName] if
     * unset — never surfaced with only a bare title.
     */
    val pickerBlurb: String get() = displayName

    /**
     * Copy strings for the shared credentialed "Add source" screen (`AddSourceScreen`). Non-null
     * for every [hasCredentials] descriptor so the screen can render title / labels / help /
     * button copy without a `when(sourceType)`. Null for zero-config singletons whose install path
     * is a one-tap picker card — they never reach the URL+username+password form.
     *
     * A completeness test (`WebSourceRegistryCompletenessTest`) enforces the invariant.
     */
    val addSourceCopy: AddSourceCopy? get() = null

    /**
     * Per-[ServerType] variant of [addSourceCopy]. Overridden by [AbsWebSourceDescriptor] to
     * return Storyteller-flavored copy when the caller is adding a Storyteller Service and
     * Audiobookshelf-flavored copy when adding a regular Audiobookshelf server (both share
     * [SourceType.ABS] until #441 splits them). Non-ABS descriptors ignore [serverType] and
     * return the base [addSourceCopy].
     */
    fun addSourceCopyFor(serverType: ServerType): AddSourceCopy? = addSourceCopy

    /**
     * Per-[ServerType] variant of the AddSourceScreen "remove source" button label. Same
     * rationale as [addSourceCopyFor] — ABS needs one label per product server ("Remove source"
     * vs "Remove Storyteller"). Falls back to [addSourceCopy]?.[AddSourceCopy.removeLabel].
     */
    fun removeLabelFor(serverType: ServerType): String? =
        addSourceCopyFor(serverType)?.removeLabel

    /**
     * Remote URL where the source hosts its own favicon, or null when we shouldn't try — either
     * because the source has no branded favicon worth fetching (Chitanka, Gutendex) or because
     * it isn't a network-backed source at all (LOCAL_FILES). Callers pair the returned URL with
     * a bundled monogram fallback (`SourceIconResolver.fallbackDrawableFor`) so a failed fetch
     * still renders something.
     *
     * [serverType] is a discriminator for ABS's Audiobookshelf/Storyteller split (they expose
     * different logo paths); other descriptors ignore it.
     */
    fun iconRemoteUrl(sourceBaseUrl: String, serverType: ServerType): String? = null

    /**
     * Resolve this source's annotation-sync namespace (#529). Defaults to [SyncNamespace.LocalOnly]
     * — the safe fallback for local / anonymous / public-catalog sources whose annotations can't
     * meaningfully leave the device. Server-backed descriptors override this to return either
     * [SyncNamespace.Configured] (when the remote user id is already persisted) or
     * [SyncNamespace.PendingRemoteId] (when the identity contract exists but the id hasn't been
     * fetched from the server yet — the repository resolves it via a per-[SourceType]
     * [RemoteUserIdResolver], persists to `Source.absUserId`, and then calls
     * [namespaceFromRemoteId] to project the freshly-resolved id into a namespace).
     */
    fun syncNamespaceFor(source: Source): SyncNamespace =
        SyncNamespace.LocalOnly("This source's books stay on this device.")

    /**
     * Project a freshly-fetched `remoteUserId` into this descriptor's namespace, without going
     * through [syncNamespaceFor] on a synthesised `source.copy(absUserId = remoteUserId)`. The
     * default (this base implementation) reads the persisted id off `source` via
     * [syncNamespaceFor]; the repository never calls it on descriptors whose
     * [syncNamespaceFor] returned [SyncNamespace.PendingRemoteId] without overriding this hook.
     *
     * Override on any descriptor whose [syncNamespaceFor] can return [SyncNamespace.PendingRemoteId].
     */
    fun namespaceFromRemoteId(source: Source, remoteUserId: String): SyncNamespace =
        syncNamespaceFor(source)
}

/**
 * User-facing strings the shared credentialed Add-Source form needs to render itself for one
 * [WebSourceDescriptor]. A new credentialed source drops in by supplying this object and its
 * Hilt `CredentialedAuthenticator` — no edits to `AddSourceScreen` required.
 */
data class AddSourceCopy(
    /** Top-bar title when adding a fresh source (e.g. "Add Audiobookshelf"). */
    val addTitle: String,
    /** Top-bar title when editing an existing source (e.g. "Edit Audiobookshelf"). */
    val editTitle: String,
    /** Label rendered on the URL text field (e.g. "Source URL"). */
    val urlLabel: String,
    /** Placeholder shown inside the URL text field when empty (e.g. "abs.example.com"). */
    val urlPlaceholder: String,
    /** Small-print paragraph above the form explaining what Riffle does with this backend. */
    val helpText: String,
    /** Label on the primary submit button when adding a new source. */
    val submitLabelAdd: String = "Connect",
    /** Label on the primary submit button when editing an existing source. */
    val submitLabelEdit: String = "Save",
    /** Label on the destructive "Remove source" button, only shown when editing. */
    val removeLabel: String,
)

/** One library row to seed on singleton-source install. See [WebSourceDescriptor.defaultLibraries]. */
data class DefaultLibrary(
    val id: String,
    val name: String,
    val mediaType: String,
)

/**
 * Static registry of every known [WebSourceDescriptor]. Composables consume this directly (no
 * Hilt injection) since descriptors are compile-time singletons. Completeness (every
 * [SourceType] resolves) is asserted by `WebSourceRegistryCompletenessTest`.
 */
object WebSourceDescriptors {
    val all: Set<WebSourceDescriptor> = setOf(
        AbsWebSourceDescriptor,
        LocalFilesWebSourceDescriptor,
        ChitankaWebSourceDescriptor,
        GutenbergWebSourceDescriptor,
        KomgaWebSourceDescriptor,
    )

    fun forType(type: SourceType): WebSourceDescriptor? =
        all.firstOrNull { it.type == type }

    fun forTypeOrError(type: SourceType): WebSourceDescriptor =
        forType(type) ?: error("no WebSourceDescriptor registered for $type")
}

/**
 * Hilt-injectable façade over [WebSourceDescriptors]. Prefer the static object at call sites;
 * inject this only when a class needs its dependencies wired through the DI graph anyway.
 */
class WebSourceRegistry(private val descriptors: Set<WebSourceDescriptor>) {
    fun all(): Set<WebSourceDescriptor> = descriptors
    fun forType(type: SourceType): WebSourceDescriptor? =
        descriptors.firstOrNull { it.type == type }
    fun forTypeOrError(type: SourceType): WebSourceDescriptor =
        forType(type) ?: error("no WebSourceDescriptor registered for $type")
}

// ABS is not a singleton — multiple servers can be added. displayName here is the family label
// ("Audiobookshelf"); per-server display in the drawer picks `source.serverType.label` because
// ABS covers both AUDIOBOOKSHELF and STORYTELLER_SERVICE product servers.
object AbsWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.ABS
    override val displayName = "Audiobookshelf"
    override val toReadSupport = ToReadSupport.Synced
    override val isSingleton = false
    override val hasCredentials = true
    override val hasNetworkHost = true
    override val addRoute = "add_source?type=audiobookshelf"
    override val pickerOrder = 0
    override val pickerBlurb = "Stream ebooks and audiobooks from your Audiobookshelf server."

    private val AUDIOBOOKSHELF_COPY = AddSourceCopy(
        addTitle = "Add Audiobookshelf",
        editTitle = "Edit Audiobookshelf",
        urlLabel = "Source URL",
        urlPlaceholder = "abs.example.com",
        helpText = "Stream ebooks and audiobooks from your Audiobookshelf server, with progress synced across devices.",
        removeLabel = "Remove source",
    )

    private val STORYTELLER_COPY = AddSourceCopy(
        addTitle = "Add Storyteller",
        editTitle = "Edit Storyteller",
        urlLabel = "Source URL",
        urlPlaceholder = "storyteller.example.com",
        helpText = "Storyteller hosts aligned ebook + audiobook \"readalouds.\" Riffle matches each readaloud to a book on your Audiobookshelf server, enabling synchronized text + audio playback inside the reader.",
        removeLabel = "Remove Storyteller",
    )

    override val addSourceCopy = AUDIOBOOKSHELF_COPY

    // Two product servers currently share [SourceType.ABS]: Audiobookshelf (the ebook/audiobook
    // library server) and Storyteller Service (the readaloud-alignment backend from ADR 0026).
    // Until #441 splits Storyteller into its own SourceType, they're discriminated at the UI
    // layer by [ServerType] — this override wires the AddSourceScreen copy to the right variant
    // so the top bar reads "Add Storyteller" not "Add Audiobookshelf" when the picker card
    // routes to the Storyteller flavour.
    override fun addSourceCopyFor(serverType: ServerType): AddSourceCopy = when (serverType) {
        ServerType.AUDIOBOOKSHELF -> AUDIOBOOKSHELF_COPY
        ServerType.STORYTELLER_SERVICE -> STORYTELLER_COPY
    }

    override fun iconRemoteUrl(sourceBaseUrl: String, serverType: ServerType): String {
        // Trim a trailing slash so `https://abs.example.com/` (a common form when the user
        // copy-pastes from the browser bar) doesn't yield `https://abs.example.com//Logo.png` —
        // most nginx installs merge doubled slashes but some deployments (merge_slashes off) 404
        // and Riffle silently falls back to the monogram fallback for a valid server.
        val base = sourceBaseUrl.trimEnd('/')
        return when (serverType) {
            ServerType.AUDIOBOOKSHELF -> "$base/Logo.png"
            ServerType.STORYTELLER_SERVICE -> "$base/apple-touch-icon.png"
        }
    }

    /**
     * Namespace prefix stamped onto every ABS sync namespace. Symmetric with
     * [KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX] so every server-backed source's namespace
     * carries a source-tag segment on the shared WebDAV target. Legacy files written before this
     * prefix existed (bare-UUID first segment) are migrated in-place by
     * [com.riffle.core.data.LegacyAbsNamespaceMigration] the next time the target enumerates the
     * base directory.
     */
    const val ABS_NAMESPACE_PREFIX = "abs_"

    // Storyteller peers exchange auth via ABS but have no independent cross-device identity
    // (their annotations already ride on the paired Audiobookshelf server's namespace via
    // reader-side matching); surface them as LocalOnly here so the sync status UI can explain
    // the state.
    override fun syncNamespaceFor(source: Source): SyncNamespace = when (source.serverType) {
        ServerType.STORYTELLER_SERVICE ->
            SyncNamespace.LocalOnly("Annotations on Storyteller readalouds sync via your paired Audiobookshelf server.")
        ServerType.AUDIOBOOKSHELF -> source.absUserId?.takeIf { it.isNotBlank() }
            ?.let { namespaceFromRemoteId(source, it) }
            ?: SyncNamespace.PendingRemoteId
    }

    override fun namespaceFromRemoteId(source: Source, remoteUserId: String): SyncNamespace =
        when (source.serverType) {
            ServerType.STORYTELLER_SERVICE ->
                SyncNamespace.LocalOnly("Annotations on Storyteller readalouds sync via your paired Audiobookshelf server.")
            ServerType.AUDIOBOOKSHELF -> SyncNamespace.Configured("$ABS_NAMESPACE_PREFIX$remoteUserId")
        }
}

object LocalFilesWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.LOCAL_FILES
    override val displayName = "Local files"
    override val toReadSupport = ToReadSupport.LocalOnly
    override val subtitle = "on this device"
    override val addRoute = "add_local_files"
    override val pickerOrder = 1
    override val pickerBlurb = "Read EPUBs and PDFs from a folder on this device."

    override fun syncNamespaceFor(source: Source): SyncNamespace =
        SyncNamespace.LocalOnly("Local files have no server account to sync annotations against.")
}

object ChitankaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.CHITANKA
    override val displayName = "Chitanka"
    override val toReadSupport = ToReadSupport.LocalOnly
    override val subtitle = "Bulgarian public library"
    override val supportingHosts = "chitanka.info · gramofonche.chitanka.info"
    override val addRoute = "add_chitanka"
    override val browseRoutePrefix = "chitanka_browse"
    override val urlPlaceholder = "https://chitanka.invalid"
    override val pickerOrder = 2
    override val pickerBlurb = "Browse Bulgarian ebooks (chitanka.info) and audiobooks (gramofonche)."
    override val defaultLibraries = listOf(
        // ids mirror ChitankaCatalog.ROOT_BOOKS / ROOT_AUDIOBOOKS; kept duplicated here so
        // `:core:domain` doesn't have to depend on `:core:catalog-chitanka`. A test in
        // `:core:data` (which depends on both) asserts they stay in sync.
        DefaultLibrary(id = "books", name = "Chitanka", mediaType = "book"),
        DefaultLibrary(id = "audiobooks", name = "Gramofonche", mediaType = "audiobook"),
    )

    override fun syncNamespaceFor(source: Source): SyncNamespace =
        SyncNamespace.LocalOnly("Chitanka is a public catalog with no per-user account to sync against.")
}

object KomgaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.KOMGA
    override val displayName = "Komga"
    override val toReadSupport = ToReadSupport.Synced
    override val isSingleton = false
    override val hasCredentials = true
    override val hasNetworkHost = true
    override val addRoute = "add_source?type=komga"
    override val pickerOrder = 4
    override val pickerBlurb = "Stream comics, manga and ebooks from your Komga server."
    override val addSourceCopy = AddSourceCopy(
        addTitle = "Add Komga",
        editTitle = "Edit Komga",
        urlLabel = "Source URL",
        urlPlaceholder = "komga.example.com",
        helpText = "Browse and read your Komga library (comics, manga and ebooks) from any Komga server.",
        removeLabel = "Remove source",
    )

    // Komga serves its own PWA favicon at /favicon.ico from the web-UI root.
    override fun iconRemoteUrl(sourceBaseUrl: String, serverType: ServerType): String =
        "${sourceBaseUrl.trimEnd('/')}/favicon.ico"

    /**
     * Namespace prefix stamped onto every Komga sync namespace. Extracted as a constant so the
     * production site and every test/fixture reference the same value — a mis-typed literal
     * (`"kmga_"`) at either end would otherwise round-trip green (AGENTS.md: "Always reference
     * constants, never the literal").
     *
     * Symmetric with [AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX]; every server-backed source
     * carries its own tag so a user id from one server cannot collide with a user id from
     * another on the shared WebDAV target.
     */
    const val KOMGA_NAMESPACE_PREFIX = "komga_"

    // Prefix with `komga_` so a Komga user id can't collide with an ABS user id (both are
    // stored in the same WebDAV share; the target's flat filename layout means the namespace
    // string alone has to distinguish source kinds).
    override fun syncNamespaceFor(source: Source): SyncNamespace =
        source.absUserId?.takeIf { it.isNotBlank() }
            ?.let { namespaceFromRemoteId(source, it) }
            ?: SyncNamespace.PendingRemoteId

    override fun namespaceFromRemoteId(source: Source, remoteUserId: String): SyncNamespace =
        SyncNamespace.Configured("$KOMGA_NAMESPACE_PREFIX$remoteUserId")
}

object GutenbergWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.GUTENBERG
    override val displayName = "Project Gutenberg"
    override val toReadSupport = ToReadSupport.LocalOnly
    override val subtitle = "Public-domain ebooks"
    override val supportingHosts = "gutenberg.org · gutendex.com"
    override val addRoute = "add_gutenberg"
    override val browseRoutePrefix = "gutenberg_browse"
    override val urlPlaceholder = "https://gutenberg.invalid"
    override val pickerOrder = 3
    override val pickerBlurb = "Browse tens of thousands of free public-domain ebooks."
    override val defaultLibraries = listOf(
        // id mirrors GutenbergCatalog.ROOT_BOOKS; see the note on ChitankaWebSourceDescriptor.
        // name = "Books" matches the pre-ADR-0044 GutenbergSourceInstaller row so existing
        // installs surviving a remove+re-add don't see the drawer library row silently rename
        // ("Project Gutenberg" as both the source header AND the sole library row read as
        // duplicated in the drawer).
        DefaultLibrary(id = "books", name = "Books", mediaType = "book"),
    )

    override fun syncNamespaceFor(source: Source): SyncNamespace =
        SyncNamespace.LocalOnly("Project Gutenberg is a public catalog with no per-user account to sync against.")
}
