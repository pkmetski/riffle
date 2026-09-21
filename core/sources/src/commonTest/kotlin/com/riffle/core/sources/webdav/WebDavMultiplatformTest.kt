package com.riffle.core.sources.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four pieces of the WebDAV client that had to stop being JVM-only before Kotlin/Native could
 * run any of it: the PROPFIND response parser (was `javax.xml` SAX), the `Last-Modified` parser
 * (was `SimpleDateFormat`), the Basic-auth header (was `java.util.Base64`) and the TLS-vs-network
 * discrimination (was `javax.net.ssl.SSLException`).
 *
 * These assertions run on `iosSimulatorArm64` as part of `:core:sources:iosSimulatorArm64Test`,
 * which is the point: the existing `jvmTest` suite proved the JVM behaviour and proved nothing
 * about the platform that had no WebDAV at all.
 */
class WebDavMultiplatformTest {

    // ── PROPFIND parsing ──────────────────────────────────────────────────────────────────

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/riffle/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/riffle/abs_src__item-1__annotations-dev-a.jsonld</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/riffle/abs_src__item-1__progress.json</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/riffle/._abs_src__item-1__progress.json</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun propfindParsingReturnsTheLastPathSegmentOfEveryHref() {
        val names = parsePropfindFilenames(propfindBody)
        assertTrue("abs_src__item-1__annotations-dev-a.jsonld" in names, names.toString())
        assertTrue("abs_src__item-1__progress.json" in names, names.toString())
    }

    @Test
    fun propfindParsingDropsTheCollectionHrefWhoseSegmentIsEmpty() {
        // "/riffle/" has an empty last segment; the SAX version filtered it and so must this one,
        // otherwise every namespace prefix match would see a spurious "" entry.
        assertFalse("" in parsePropfindFilenames(propfindBody))
    }

    @Test
    fun propfindParsingDropsAppleDoubleSidecars() {
        val names = parsePropfindFilenames(propfindBody)
        assertFalse(names.any { it.startsWith("._") }, names.toString())
    }

    @Test
    fun propfindParsingHandlesADefaultNamespacedDocument() {
        val body = """
            <multistatus xmlns="DAV:">
              <response><href>/dav/one__two__progress.json</href></response>
            </multistatus>
        """.trimIndent()
        assertEquals(listOf("one__two__progress.json"), parsePropfindFilenames(body))
    }

    @Test
    fun propfindParsingReturnsEmptyForBlankAndUnparseableBodies() {
        assertEquals(emptyList(), parsePropfindFilenames(""))
        assertEquals(emptyList(), parsePropfindFilenames("   "))
    }

    /**
     * The exact shape a real Synology DSM WebDAV share returns (DSM 7, Apache + mod_dav), with
     * the item ids replaced by synthetic ones.
     *
     * Two things here are not in the hand-written fixture above and both broke the naive swap
     * from SAX to a DOM parser: the prefix is uppercase `D:` (SAX matched on `localName`, ksoup
     * matches on the tag name and has to strip the prefix itself), and `resourcetype` arrives
     * under a *second* prefix bound to the same namespace (`lp1:`), which a prefix-equality
     * check would treat as a different element.
     */
    private val synologyPropfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
        <D:response xmlns:lp1="DAV:">
        <D:href>/Annotations/</D:href>
        <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response>
        <D:response xmlns:lp1="DAV:">
        <D:href>/Annotations/chitanka__book.one__audio_progress.json</D:href>
        <D:propstat><D:prop><lp1:resourcetype/></D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response>
        <D:response xmlns:lp1="DAV:">
        <D:href>/Annotations/._abs_src__item-1__annotations-dev-a.jsonld</D:href>
        <D:propstat><D:prop><lp1:resourcetype/></D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun propfindParsingHandlesARealSynologyResponse() {
        val names = parsePropfindFilenames(synologyPropfindBody)
        assertEquals(listOf("chitanka__book.one__audio_progress.json"), names)
    }

    @Test
    fun httpDateParsesAStampInTheFormTheTestServerActuallySends() {
        // Captured verbatim from the Synology share's Last-Modified header.
        assertEquals(1_789_838_954_000L, parseHttpDate("Sat, 19 Sep 2026 17:29:14 GMT"))
    }

    // ── Last-Modified parsing ─────────────────────────────────────────────────────────────

    @Test
    fun httpDateParsesAnRfc1123GmtStamp() {
        // 2025-08-18T12:00:00Z. The exact epoch is pinned rather than `> 0` because the
        // reconciler compares this against the local clock — an hour out and last-update-wins
        // silently picks the wrong side.
        assertEquals(1_755_518_400_000L, parseHttpDate("Mon, 18 Aug 2025 12:00:00 GMT"))
    }

    @Test
    fun httpDateHandlesLeapYearsAndTheEpochItself() {
        assertEquals(0L, parseHttpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(951_825_600_000L, parseHttpDate("Tue, 29 Feb 2000 12:00:00 GMT"))
        assertEquals(1_709_208_000_000L, parseHttpDate("Thu, 29 Feb 2024 12:00:00 GMT"))
    }

    @Test
    fun httpDateAppliesANumericZoneOffset() {
        val gmt = parseHttpDate("Mon, 18 Aug 2025 12:00:00 GMT")!!
        assertEquals(gmt - 2 * 3_600_000L, parseHttpDate("Mon, 18 Aug 2025 12:00:00 +0200"))
        assertEquals(gmt + 5 * 3_600_000L, parseHttpDate("Mon, 18 Aug 2025 12:00:00 -0500"))
    }

    @Test
    fun httpDateRejectsGarbageRatherThanGuessing() {
        assertEquals(null, parseHttpDate(""))
        assertEquals(null, parseHttpDate("not a date"))
        assertEquals(null, parseHttpDate("Mon, 18 Smarch 2025 12:00:00 GMT"))
        assertEquals(null, parseHttpDate("Mon, 18 Aug 2025 12:00 GMT"))
        assertEquals(null, parseHttpDate("Mon, 18 Aug 2025 25:00:00 GMT"))
    }

    // ── Basic auth ────────────────────────────────────────────────────────────────────────

    @Test
    fun basicAuthHeaderMatchesTheJavaBase64Encoding() {
        // "user:pass" -> dXNlcjpwYXNz. Standard RFC 4648 alphabet with padding, exactly what
        // java.util.Base64.getEncoder() produced.
        assertEquals("Basic dXNlcjpwYXNz", webDavBasicAuthHeader("user", "pass"))
    }

    @Test
    fun basicAuthHeaderEncodesNonAsciiCredentialsAsUtf8() {
        // "tëst:pä" in UTF-8 is 74 c3 ab 73 74 3a 70 c3 a4.
        assertEquals("Basic dMOrc3Q6cMOk", webDavBasicAuthHeader("tëst", "pä"))
    }

    @Test
    fun basicAuthHeaderPadsCorrectlyForEveryInputLengthMod3() {
        assertEquals("Basic YTpi", webDavBasicAuthHeader("a", "b"))
        assertEquals("Basic YTpiYw==", webDavBasicAuthHeader("a", "bc"))
        assertEquals("Basic YTpiY2Q=", webDavBasicAuthHeader("a", "bcd"))
    }

    // ── TLS-vs-network classification ─────────────────────────────────────────────────────

    @Test
    fun tlsMarkersRecogniseTheUsualCertificateFailures() {
        assertTrue(looksLikeTlsFailure("An SSL error has occurred"))
        assertTrue(looksLikeTlsFailure("The certificate for this server is invalid"))
        assertTrue(looksLikeTlsFailure("TLS handshake failed"))
        assertTrue(looksLikeTlsFailure("NSURLErrorDomain error -1200"))
    }

    @Test
    fun tlsMarkersDoNotFireOnAPlainConnectionFailure() {
        assertFalse(looksLikeTlsFailure(null))
        assertFalse(looksLikeTlsFailure(""))
        assertFalse(looksLikeTlsFailure("Could not connect to the server"))
        assertFalse(looksLikeTlsFailure("The request timed out"))
    }

    // ── URL parsing ───────────────────────────────────────────────────────────────────────

    @Test
    fun baseUrlParsingRejectsBlankInput() {
        assertEquals(null, parseWebDavBaseUrl(""))
        assertEquals(null, parseWebDavBaseUrl("   "))
    }

    @Test
    fun baseUrlParsingAcceptsAnOrdinaryWebDavUrl() {
        val url = parseWebDavBaseUrl("  https://dav.example.com/riffle  ")
        assertEquals("dav.example.com", url?.host)
    }
}
