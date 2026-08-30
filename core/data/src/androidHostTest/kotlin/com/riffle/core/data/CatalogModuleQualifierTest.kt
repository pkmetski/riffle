package com.riffle.core.data

import com.riffle.core.models.SourceType
import org.junit.Test
import java.io.File

class CatalogModuleQualifierTest {

    /**
     * Every unbounded-catalog (web) source's `CatalogFactory` entry must inject the
     * `named("webSourceHttpClient")`-qualified Ktor `HttpClient`, not the app-wide default. The
     * qualified client is the only one backed by the ADR 0052 disk cache +
     * `ForceCacheHeadersInterceptor` + `OfflineStaleFallbackInterceptor`. Missing the qualifier
     * turns filter switches into uncached, non-retriable Gutendex/… round-trips that fail fast on
     * transient IO — the "couldn't reach Project Gutenberg" error the user hit after 1–2 filter
     * taps (#516/#520).
     *
     * Regression test for #516: `provideGutenbergCatalogFactory` shipped without the qualifier
     * and Gutenberg fetches bypassed the cache and stale-fallback interceptor entirely.
     * Ported from `CatalogModuleQualifierTest` (Hilt) to Koin: checks `CoreDataKoinModules.kt`
     * instead of the deleted `CatalogModule.kt`.
     */
    @Test
    fun `every unbounded-catalog provider uses the WebSource OkHttp qualifier`() {
        val source = koinModulesSource()

        // Find the catalog map block (single<Map<SourceType, CatalogFactory>> { mapOf(...) })
        val catalogMapRegex = Regex(
            """single<Map<SourceType,\s*CatalogFactory>>\s*\{(.*?)^    \}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
        )
        val catalogBlock = catalogMapRegex.find(source)?.groupValues?.get(1)
        checkNotNull(catalogBlock) { "Could not find single<Map<SourceType, CatalogFactory>> block in CoreDataKoinModules.kt" }

        // For each unbounded-catalog SourceType, verify the block contains named("webSourceHttpClient")
        // near that type's factory entry.
        val unboundedTypes = SourceType.entries.filter { it.isUnboundedCatalog }
        check(unboundedTypes.isNotEmpty()) { "No unbounded-catalog SourceTypes found — SourceType.isUnboundedCatalog may be broken" }

        // Split the catalog block by "SourceType." to get per-entry segments.
        // Each segment starts with the type name, e.g. "CHITANKA to ChitankaCatalogFactory(...),".
        val segments = catalogBlock.split("SourceType.")
        check(segments.size > 1) { "Could not split catalog map block by SourceType." }

        val offenders = mutableListOf<String>()
        for (segment in segments.drop(1)) {
            // Extract the type name (word at the start of this segment).
            val typeName = segment.takeWhile { it.isLetterOrDigit() || it == '_' }
            val type = runCatching { SourceType.valueOf(typeName) }.getOrNull() ?: continue
            if (!type.isUnboundedCatalog) continue
            val hasHttpClientParam = segment.contains("httpClient") || segment.contains("sharedHttpClient")
            if (!hasHttpClientParam) continue
            // Koin module uses named(WEB_SOURCE_HTTP_CLIENT) constant or the literal "webSourceHttpClient"
            val usesWebSourceNamed = segment.contains("named(WEB_SOURCE_HTTP_CLIENT)") ||
                segment.contains("webSourceHttpClient")
            if (!usesWebSourceNamed) {
                offenders += "$type"
            }
        }
        assert(offenders.isEmpty()) {
            "Unbounded-catalog entries missing named(\"webSourceHttpClient\") for their HttpClient: $offenders\n" +
                "See #516/#520 — missing the named qualifier bypasses the disk cache and stale-fallback interceptor."
        }
    }

    private fun koinModulesSource(): String {
        val candidates = listOf(
            "core/data/src/androidMain/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
            "src/androidMain/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
            "src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
        )
        for (rel in candidates) {
            val f = File(rel)
            if (f.exists()) return f.readText()
        }
        val cwd = File(".").absolutePath
        val fromRoot = generateSequence(File(cwd)) { it.parentFile }
            .flatMap { root ->
                listOf(
                    File(root, "core/data/src/androidMain/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt"),
                    File(root, "core/data/src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt"),
                )
            }
            .firstOrNull { it.exists() }
        checkNotNull(fromRoot) { "CoreDataKoinModules.kt not found from cwd=$cwd" }
        return fromRoot.readText()
    }
}
