package com.riffle.core.data

import com.riffle.core.models.SourceType
import org.junit.Test
import java.io.File

class CatalogModuleQualifierTest {

    /**
     * Every unbounded-catalog (web) source's `CatalogFactory` registration must use the
     * web-source HTTP client (`named(WEB_SOURCE_HTTP_CLIENT)`) rather than the app-wide default.
     * The named client is the only one backed by the ADR 0052 disk cache +
     * `ForceCacheHeadersInterceptor` + `OfflineStaleFallbackInterceptor`. Missing the named
     * qualifier turns filter switches into uncached, non-retriable Gutendex/… round-trips that
     * fail fast on transient IO — the "couldn't reach Project Gutenberg" error the user hit after
     * 1–2 filter taps (#516/#520).
     *
     * Regression test for #516: the Gutenberg factory registration shipped without the named
     * qualifier and Gutenberg fetches bypassed the cache and stale-fallback interceptor entirely.
     * Previously checked CatalogModule.kt (Hilt); now checks CoreDataKoinModules.kt (Koin).
     */
    @Test
    fun `every unbounded-catalog factory uses the web source named HTTP client`() {
        val source = coreDataKoinModulesSource()
        val webSourceClientConst = "WEB_SOURCE_HTTP_CLIENT"

        // Find all `SourceType.X to XCatalogFactory(` blocks and the closing `)` for each.
        // We extract the argument block between `CatalogFactory(` and the matching `)`.
        val registrationRegex = Regex(
            """SourceType\.(\w+)\s+to\s+\w+CatalogFactory\(([^)]*)\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val matches = registrationRegex.findAll(source).toList()
        check(matches.isNotEmpty()) { "Could not find any CatalogFactory registrations in CoreDataKoinModules" }

        val offenders = mutableListOf<String>()
        for (m in matches) {
            val typeName = m.groupValues[1]
            val args = m.groupValues[2]
            val type = runCatching { SourceType.valueOf(typeName) }.getOrNull() ?: continue
            if (!type.isUnboundedCatalog) continue
            if (!args.contains(webSourceClientConst)) {
                offenders += "$type"
            }
        }
        assert(offenders.isEmpty()) {
            "Unbounded-catalog factory registrations missing named($webSourceClientConst): $offenders"
        }
    }

    private fun coreDataKoinModulesSource(): String {
        val candidates = listOf(
            "core/data/src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
            "src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
            "../../src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt",
        )
        for (rel in candidates) {
            val f = File(rel)
            if (f.exists()) return f.readText()
        }
        val cwd = File(".").absolutePath
        val fromRoot = generateSequence(File(cwd)) { it.parentFile }
            .map { File(it, "core/data/src/main/kotlin/com/riffle/core/data/di/CoreDataKoinModules.kt") }
            .firstOrNull { it.exists() }
        checkNotNull(fromRoot) { "CoreDataKoinModules.kt not found from cwd=$cwd" }
        return fromRoot.readText()
    }
}
