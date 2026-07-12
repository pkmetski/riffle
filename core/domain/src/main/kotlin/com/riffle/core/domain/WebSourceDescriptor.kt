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
 * The descriptor holds display + capability data only. Icons live in `:app` because
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
}

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
}

object LocalFilesWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.LOCAL_FILES
    override val displayName = "Local files"
    override val subtitle = "on this device"
}

object ChitankaWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.CHITANKA
    override val displayName = "Chitanka"
    override val subtitle = "Bulgarian public library"
    // Static host list used by the settings row's supporting text.
    val supportingHosts: String = "chitanka.info · gramofonche.chitanka.info"
}

object GutenbergWebSourceDescriptor : WebSourceDescriptor {
    override val type = SourceType.GUTENBERG
    override val displayName = "Project Gutenberg"
    override val subtitle = "Public-domain ebooks"
    val supportingHosts: String = "gutenberg.org · gutendex.com"
}
