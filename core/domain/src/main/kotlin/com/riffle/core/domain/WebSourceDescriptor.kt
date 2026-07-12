package com.riffle.core.domain

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
interface WebSourceDescriptor {
    val type: SourceType

    /** Human-visible name — drawer header, source-picker card, settings row. */
    val displayName: String

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
    override val isSingleton = false
    override val hasCredentials = true
    override val hasNetworkHost = true
    override val addRoute = "add_source?type=audiobookshelf"
    override val pickerOrder = 0
    override val pickerBlurb = "Stream ebooks and audiobooks from your Audiobookshelf server."
    override val addSourceCopy = AddSourceCopy(
        addTitle = "Add Audiobookshelf",
        editTitle = "Edit Audiobookshelf",
        urlLabel = "Source URL",
        urlPlaceholder = "abs.example.com",
        helpText = "Stream ebooks and audiobooks from your Audiobookshelf server, with progress synced across devices.",
        removeLabel = "Remove source",
    )
}

object LocalFilesWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.LOCAL_FILES
    override val displayName = "Local files"
    override val subtitle = "on this device"
    override val addRoute = "add_local_files"
    override val pickerOrder = 1
    override val pickerBlurb = "Read EPUBs and PDFs from a folder on this device."
}

object ChitankaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.CHITANKA
    override val displayName = "Chitanka"
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
}

object KomgaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.KOMGA
    override val displayName = "Komga"
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
}

object GutenbergWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.GUTENBERG
    override val displayName = "Project Gutenberg"
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
}
