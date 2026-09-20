package com.riffle.core.catalog.chitanka

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

/**
 * Differential test for the port of [ChitankaScraper.toAbsolute] from `java.net.URI` to the
 * multiplatform [resolveAgainstBase].
 *
 * `java.net.URI` is JVM-only, so this can never run on iOS — it is not the iOS counterpart for
 * anything. Its job is narrower and one-off in nature: prove that the shared implementation is
 * byte-identical to the JVM one it replaced, across every `href`/`src` the checked-in fixtures
 * contain plus the awkward shapes (dot segments, `//`, query-only, fragment-only, Cyrillic,
 * percent-escapes, characters `URI` refuses to parse) that the fixtures happen not to cover.
 *
 * The behaviour itself is pinned for both platforms by `ChitankaUrlResolutionTest` in
 * `commonTest`, which runs on `iosSimulatorArm64` as well as the JVM.
 */
class ChitankaUrlResolutionJvmParityTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private val bases = listOf(
        "https://chitanka.info/",
        "https://chitanka.info/text/44723-abu-hasan",
        "https://gramofonche.chitanka.info/prikazki/grycki-legendi/",
        "https://gramofonche.chitanka.info/prikazki/",
        "https://chitanka.info/books/category/detsko",
    )

    /** What `toAbsolute` used to do, verbatim, including its fallback. */
    private fun legacy(pageUrl: String, safeHref: String): String = try {
        URI(pageUrl).resolve(safeHref).toASCIIString()
    } catch (_: Exception) {
        FALLBACK
    }

    private fun ported(pageUrl: String, safeHref: String): String = try {
        resolveAgainstBase(pageUrl, safeHref)
    } catch (_: Exception) {
        FALLBACK
    }

    @Test
    fun portedResolverMatchesJavaUriOnEveryFixtureHref() {
        val fixtures = listOf(
            "chitanka-categories.html", "chitanka-category.html", "chitanka-search.html",
            "chitanka-series-alpha.html", "chitanka-text-detail.html",
            "gramofonche-detail.html", "gramofonche-prikazki.html",
        )
        val attribute = Regex("""(?:href|src)\s*=\s*"([^"]*)"""")
        val hrefs = fixtures
            .flatMap { name -> attribute.findAll(fixture(name)).map { it.groupValues[1] } }
            // Absolute and scheme-relative hrefs never reach the resolver — toAbsolute
            // short-circuits both. Spaces are pre-encoded by toAbsolute, so do the same here.
            .filter { it.isNotEmpty() && !it.startsWith("http") && !it.startsWith("//") }
            .map { it.replace(" ", "%20") }
            .distinct()
        assertEquals("fixtures should yield a large href corpus", true, hrefs.size > 500)
        for (base in bases) {
            for (href in hrefs) {
                assertEquals("base=$base href=$href", legacy(base, href), ported(base, href))
            }
        }
    }

    @Test
    fun portedResolverMatchesJavaUriOnAwkwardReferenceShapes() {
        val hrefs = listOf(
            "/book/1", "./dir/a.mp3", "../up/a.mp3", "a.mp3", "dir/sub/../a.mp3",
            "/search?q=%D0%B0&page=2", "?page=3", "#frag", "/a/b/", "./", "../",
            "/книга/1", "книга/иван.mp3", "/a//b", "/a/./b/../c",
            "/x.epub#p1", "/x?y=1#z", "", "/",
            "/a%20b/c", "1-Vremeto%20na%20prabogovete.mp3",
            "bad|char", "bad^char", "bad{char}", "bad\\char", "half%2", "%zz",
            "b//c", "./b//c", "./b/./c", "./b/../c", "../../a", ".", "..",
            "./a/", "../a/", "sub/../../x", "./x#f", "./x?q=1", "a/b/c/../../d",
            "a/..", "a/b/..", "a/../", "./.", "b/.", "b/./", "a/b/../..", "a/b/../../..",
            "x/y//z/../w", "./%D0%B0/b", "тест/а.mp3", "a/../b/../c",
        )
        for (base in bases) {
            for (href in hrefs) {
                assertEquals("base=$base href=$href", legacy(base, href), ported(base, href))
            }
        }
    }

    private companion object {
        /** Stand-in for "both implementations threw", which `toAbsolute` turns into `BASE + href`. */
        const val FALLBACK = "<<threw>>"
    }
}
