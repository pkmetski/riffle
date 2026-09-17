package com.riffle.shared.testing

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sources.SourceAdapter
import kotlinx.coroutines.runBlocking
import org.koin.mp.KoinPlatform
import platform.Foundation.NSProcessInfo

/**
 * Adds the source requested through `--RIFFLE_SEED_SOURCE=...` (see [TestSourceSeed]) before the
 * first frame, using the same [SourceAdapter.authenticate] → [SourceRepository.commit] path the
 * add-source screen runs. Called from `RiffleApp.init` right after Koin starts; a no-op without the
 * argument. Blocking is intentional: the start destination is resolved from the database on first
 * composition, and the harness expects the library home, not the source picker.
 */
@Suppress("unused") // Called from Swift: IosTestSourceSeederKt.seedTestSourceFromLaunchArguments()
fun seedTestSourceFromLaunchArguments() {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val seed = TestSourceSeed.parse(arguments) ?: return
    val koin = KoinPlatform.getKoin()
    val adapters: Map<SourceType, SourceAdapter> = koin.get()
    val repository: SourceRepository = koin.get()
    val adapter = adapters[seed.type] ?: run {
        println("RIFFLE_SEED_SOURCE: no SourceAdapter bound for ${seed.type}")
        return
    }
    val url = SourceUrl.parse(seed.url) ?: run {
        println("RIFFLE_SEED_SOURCE: unparseable url ${seed.url}")
        return
    }
    runBlocking {
        val result = adapter.authenticate(
            url = url,
            username = seed.username,
            password = seed.password,
            insecureAllowed = seed.insecureAllowed,
            // Only ABS distinguishes server variants; other adapters ignore the value.
            serverType = ServerType.AUDIOBOOKSHELF,
        )
        when (result) {
            is AuthenticateResult.Success -> {
                when (val commit = repository.commit(result.pending, hiddenLibraryIds = emptySet())) {
                    is CommitSourceResult.Success -> println("RIFFLE_SEED_SOURCE: seeded ${seed.type} ${seed.url}")
                    is CommitSourceResult.Failure -> println("RIFFLE_SEED_SOURCE: commit failed: ${commit.cause}")
                }
            }
            else -> println("RIFFLE_SEED_SOURCE: authenticate failed: $result")
        }
    }
}
