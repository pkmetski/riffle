package com.riffle.core.data

import com.riffle.core.models.SourceType
import org.junit.Test
import java.io.File

class CatalogModuleQualifierTest {

    /**
     * Every unbounded-catalog (web) source's `CatalogFactory` provider must inject the
     * `@WebSourceOkHttpClient`-qualified `OkHttpClient`, not the app-wide default. The qualified
     * client is the only one carrying the ADR 0043 disk cache + `ForceCacheHeadersInterceptor` +
     * `OfflineStaleFallbackInterceptor`. Missing the qualifier turns filter switches into
     * uncached, non-retriable Gutendex/… round-trips that fail fast on transient IO — the
     * "couldn't reach Project Gutenberg" error the user hit after 1–2 filter taps (#516/#520).
     *
     * Regression test for #516: `provideGutenbergCatalogFactory` shipped without the qualifier
     * and Gutenberg fetches bypassed the cache and stale-fallback interceptor entirely.
     */
    @Test
    fun `every unbounded-catalog provider uses the WebSource OkHttp qualifier`() {
        val source = catalogModuleSource()
        val providerRegex = Regex(
            """@SourceTypeKey\(SourceType\.(\w+)\)\s*fun\s+(\w+)\s*\(([^)]*)\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val matches = providerRegex.findAll(source).toList()
        check(matches.isNotEmpty()) { "Could not find any @SourceTypeKey providers in CatalogModule" }

        val offenders = mutableListOf<String>()
        for (m in matches) {
            val typeName = m.groupValues[1]
            val fnName = m.groupValues[2]
            val params = m.groupValues[3]
            val type = runCatching { SourceType.valueOf(typeName) }.getOrNull() ?: continue
            if (!type.isUnboundedCatalog) continue
            val okHttpParam = params.split(",").firstOrNull { it.contains("OkHttpClient") }
                ?: error("Provider $fnName for $type has no OkHttpClient parameter")
            if (!okHttpParam.contains("@WebSourceOkHttpClient")) {
                offenders += "$type → $fnName"
            }
        }
        assert(offenders.isEmpty()) {
            "Unbounded-catalog providers missing @WebSourceOkHttpClient: $offenders"
        }
    }

    private fun catalogModuleSource(): String {
        val candidates = listOf(
            "core/data/src/main/kotlin/com/riffle/core/data/di/modules/CatalogModule.kt",
            "src/main/kotlin/com/riffle/core/data/di/modules/CatalogModule.kt",
            "../../src/main/kotlin/com/riffle/core/data/di/modules/CatalogModule.kt",
        )
        for (rel in candidates) {
            val f = File(rel)
            if (f.exists()) return f.readText()
        }
        val cwd = File(".").absolutePath
        val fromRoot = generateSequence(File(cwd)) { it.parentFile }
            .map { File(it, "core/data/src/main/kotlin/com/riffle/core/data/di/modules/CatalogModule.kt") }
            .firstOrNull { it.exists() }
        checkNotNull(fromRoot) { "CatalogModule.kt not found from cwd=$cwd" }
        return fromRoot.readText()
    }
}
