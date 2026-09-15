package com.riffle.core.models

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import com.riffle.core.models.SourceUrl

class SourceUrlTest {

    @Test
    fun `https url is accepted`() {
        val url = SourceUrl.parse("https://abs.example.com")
        assertEquals(url?.value, "https://abs.example.com")
    }

    @Test
    fun `http url is accepted`() {
        val url = SourceUrl.parse("http://192.168.1.100:13378")
        assertEquals(url?.value, "http://192.168.1.100:13378")
    }

    @Test
    fun `trailing slash is stripped`() {
        val url = SourceUrl.parse("https://abs.example.com/")
        assertEquals(url?.value, "https://abs.example.com")
    }

    @Test
    fun `multiple trailing slashes are stripped`() {
        val url = SourceUrl.parse("https://abs.example.com///")
        assertEquals(url?.value, "https://abs.example.com")
    }

    @Test
    fun `empty string returns null`() {
        assertNull(SourceUrl.parse(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(SourceUrl.parse("   "))
    }

    @Test
    fun `url without scheme returns null`() {
        assertNull(SourceUrl.parse("abs.example.com"))
    }

    @Test
    fun `ftp scheme returns null`() {
        assertNull(SourceUrl.parse("ftp://abs.example.com"))
    }

    @Test
    fun `url with path prefix is preserved`() {
        val url = SourceUrl.parse("https://abs.example.com/audiobookshelf")
        assertEquals(url?.value, "https://abs.example.com/audiobookshelf")
    }

    @Test
    fun `authority returns host for url without port`() {
        assertEquals(SourceUrl.parse("https://abs.example.com")!!.authority(), "abs.example.com")
    }

    @Test
    fun `authority returns host and port when port is present`() {
        assertEquals(SourceUrl.parse("http://media-server:13378")!!.authority(), "media-server:13378")
    }

    @Test
    fun `authority strips path`() {
        assertEquals(SourceUrl.parse("https://abs.example.com/audiobookshelf")!!.authority(), "abs.example.com")
    }

    @Test
    fun `authority handles ipv4 with port`() {
        assertEquals(SourceUrl.parse("http://192.168.1.100:13378")!!.authority(), "192.168.1.100:13378")
    }

    @Test
    fun `authority strips user info`() {
        assertEquals(
            "abs.example.com:13378",
            SourceUrl.parse("https://reader:secret@abs.example.com:13378/books")!!.authority(),
        )
    }

    @Test
    fun `authority strips query and fragment without path`() {
        assertEquals(
            "abs.example.com",
            SourceUrl.parse("https://abs.example.com?source=drawer#active")!!.authority(),
        )
    }

    @Test
    fun `two equal urls are equal`() {
        val a = SourceUrl.parse("https://abs.example.com")
        val b = SourceUrl.parse("https://abs.example.com")
        assertEquals(a, b)
    }
}
