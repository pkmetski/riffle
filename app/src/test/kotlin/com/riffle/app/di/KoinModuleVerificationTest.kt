package com.riffle.app.di

import com.riffle.app.riffleKoinModules
import org.junit.Assert.fail
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.core.module.flatten
import kotlin.reflect.KVisibility
import kotlin.reflect.full.primaryConstructor

/**
 * Verifies the full production Koin graph resolves: for every definition bound to a
 * concrete class, each primary-constructor parameter type must be bound somewhere in
 * [riffleKoinModules] (any qualifier).
 *
 * Hilt validated the DI graph at compile time; Koin resolves lazily at runtime, so a
 * missing binding otherwise only surfaces as an InstanceCreationException when a user
 * (or a 20-minute CI harness run) first navigates to the affected screen — this is
 * exactly how the AddSourceViewModel and LibraryItemsViewModel crashes shipped after
 * the Hilt→Koin migration. This test restores that validation at JVM unit-test speed.
 *
 * Koin's own `Module.verify()` is not usable here: it reflects over ALL public
 * constructors of platform-bound definitions too (e.g. the qualifier-bound `File`
 * cache-dir singletons), and its circular-injection check false-positives on
 * `File(File, String)` with no public escape hatch. This verifier applies the same
 * constructor-vs-index technique to app classes only.
 *
 * Limitation (same as Koin's verify): definitions whose bound type is an interface
 * have no constructor to reflect, so their lambda bodies are not checked. All
 * ViewModel definitions bind concrete classes, which is where every runtime DI crash
 * so far has occurred.
 */
class KoinModuleVerificationTest {

    /** Types legitimately resolvable at runtime but invisible to the module index. */
    private val allowedTypes = setOf(
        "android.content.Context", // provided by androidContext() at startKoin time
        "android.app.Application",
        "androidx.lifecycle.SavedStateHandle", // provided by Koin's viewModel factory
    )

    /**
     * `<definition class>.<param>` pairs whose Koin lambda constructs the value inline
     * (not via get()) — reflection can't see lambda bodies, so these would be flagged
     * as missing. Keep in sync with the module definitions.
     */
    private val inlineProvidedParams = setOf(
        "com.riffle.core.sync.ProgressSweep.ebookReconciler",
        "com.riffle.core.sync.ProgressSweep.audioReconciler",
        "com.riffle.core.sync.ProgressSweep.bookmarkLedger",
        "com.riffle.core.sync.ProgressSweep.bookmarkReconcile",
        "com.riffle.core.data.dictionary.PackDownloader.converter",
        // Built inline from SavedStateHandle in the viewModel{} lambda (nav arg), not from get().
        "com.riffle.feature.library.LibrarySectionViewModel.sectionType",
    )

    @OptIn(KoinInternalApi::class)
    @Test
    fun productionKoinGraphResolves() {
        val modules: Set<Module> = flatten(riffleKoinModules())
        // Index of bound type names, normalized for EXACT matching: an IndexKey is
        // "<binary class name>[:qualifier...]", and binary names use '$' for nested
        // classes (FormattingSession$Factory) where KClass.qualifiedName uses '.'.
        // Exact matching matters: substring matching would let a bound concrete
        // 'AudiobookBundleApiImpl' shadow a missing 'AudiobookBundleApi' interface
        // binding — precisely the bug class this test exists to catch.
        val index: Set<String> = modules
            .flatMap { it.mappings.keys }
            .map { it.substringBefore(':').replace('$', '.') }
            .toSet()
        val problems = mutableListOf<String>()

        val factories = modules.flatMap { it.mappings.values }.toSet()
        check(factories.size > 100) { "Suspiciously small Koin graph (${factories.size} definitions) — did module aggregation change?" }

        factories.forEach { factory ->
            val boundType = factory.beanDefinition.primaryType
            val name = boundType.qualifiedName ?: return@forEach
            // Only reflect on our own classes: third-party bound types (ktor, Readium…)
            // are constructed by their lambdas with values the index can't see.
            if (!name.startsWith("com.riffle.")) return@forEach
            if (boundType.java.isInterface) return@forEach

            val constructor = boundType.primaryConstructor ?: return@forEach
            if (constructor.visibility != KVisibility.PUBLIC) return@forEach

            constructor.parameters.forEach params@{ param ->
                if (param.isOptional) return@params
                val paramType = param.type.classifier as? kotlin.reflect.KClass<*> ?: return@params
                val paramName = paramType.qualifiedName ?: return@params
                // Platform/stdlib param types (String, List, Function0, CoroutineScope…)
                // are supplied inline by definition lambdas, not resolved from the graph.
                if (!paramName.startsWith("com.riffle.") && !paramName.startsWith("org.readium.")) return@params
                if (paramName in allowedTypes) return@params
                if ("$name.${param.name}" in inlineProvidedParams) return@params
                if (paramName !in index) {
                    problems += "'$name' needs '${param.name}: $paramName' but no Koin definition binds that type"
                }
            }
        }

        // Duplicate index keys: Koin indexes by ERASED KClass + qualifier, so e.g. two
        // unqualified Map<...> definitions collide and the last-loaded silently overrides
        // the first for EVERY injection point of that erased type (this handed
        // DefaultCatalogRegistry a map of SourceAdapters → ClassCastException). Generic
        // bindings must carry a named() qualifier.
        val duplicates = modules.flatMap { it.mappings.keys }
            .groupingBy { it }.eachCount().filterValues { it > 1 }
        duplicates.forEach { (key, count) ->
            problems += "index key '$key' is defined $count times — later definitions silently override earlier ones"
        }

        // Types resolved via field injection (by inject() in Activities/Services) or
        // GlobalContext.get() in RiffleApplication — no constructor for reflection to
        // walk, so pin them explicitly. LocalFilesFolderWatcher going unbound crashed
        // the production app at startup while the harness (plain test Application)
        // stayed green.
        val fieldInjected = listOf(
            "com.riffle.core.data.localfiles.LocalFilesFolderWatcher", // RiffleApplication.onCreate
            "com.riffle.app.feature.audio.MediaSourceRegistry", // AudioPlayerService
            "com.riffle.app.feature.audio.MediaItemRestorerRegistry", // AudioPlayerService
            "com.riffle.core.data.LocalStoreMigrator", // RiffleApplication.onCreate
            "com.riffle.core.data.AnnotationSweep", // RiffleApplication.onCreate
            "com.riffle.core.sync.ProgressSweep", // RiffleApplication.onCreate
        )
        fieldInjected.forEach { type ->
            if (type !in index) problems += "'$type' is resolved via inject()/get() at a field or startup site but has no Koin definition"
        }

        if (problems.isNotEmpty()) {
            fail(
                "Koin graph has ${problems.size} unresolvable constructor dependenc" +
                    (if (problems.size == 1) "y" else "ies") +
                    " (would crash with InstanceCreationException at runtime):\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
    }
}
