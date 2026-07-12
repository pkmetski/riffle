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
}

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
}

object LocalFilesWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.LOCAL_FILES
    override val displayName = "Local files"
    override val subtitle = "on this device"
    override val addRoute = "add_local_files"
}

object ChitankaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.CHITANKA
    override val displayName = "Chitanka"
    override val subtitle = "Bulgarian public library"
    override val supportingHosts = "chitanka.info · gramofonche.chitanka.info"
    override val addRoute = "add_chitanka"
    override val browseRoutePrefix = "chitanka_browse"
    override val urlPlaceholder = "https://chitanka.invalid"
    override val defaultLibraries = listOf(
        // ids mirror ChitankaCatalog.ROOT_BOOKS / ROOT_AUDIOBOOKS; kept duplicated here so
        // `:core:domain` doesn't have to depend on `:core:catalog-chitanka`. A test in
        // `:core:data` (which depends on both) asserts they stay in sync.
        DefaultLibrary(id = "books", name = "Chitanka", mediaType = "book"),
        DefaultLibrary(id = "audiobooks", name = "Gramofonche", mediaType = "audiobook"),
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
    override val defaultLibraries = listOf(
        // id mirrors GutenbergCatalog.ROOT_BOOKS; see the note on ChitankaWebSourceDescriptor.
        DefaultLibrary(id = "books", name = "Project Gutenberg", mediaType = "book"),
    )
}
