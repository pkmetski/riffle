package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceUrlTest {

    @Test
    fun `https url is accepted`() {
        val url = SourceUrl.parse("https://abs.example.com")
        assertEquals("https://abs.example.com", url?.value)
    }

    @Test
    fun `http url is accepted`() {
        val url = SourceUrl.parse("http://192.168.1.100:13378")
        assertEquals("http://192.168.1.100:13378", url?.value)
    }

    @Test
    fun `trailing slash is stripped`() {
        val url = SourceUrl.parse("https://abs.example.com/")
        assertEquals("https://abs.example.com", url?.value)
    }

    @Test
    fun `multiple trailing slashes are stripped`() {
        val url = SourceUrl.parse("https://abs.example.com///")
        assertEquals("https://abs.example.com", url?.value)
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
        assertEquals("https://abs.example.com/audiobookshelf", url?.value)
    }

    @Test
    fun `authority returns host for url without port`() {
        assertEquals("abs.example.com", SourceUrl.parse("https://abs.example.com")!!.authority())
    }

    @Test
    fun `authority returns host and port when port is present`() {
        assertEquals("media-server:13378", SourceUrl.parse("http://media-server:13378")!!.authority())
    }

    @Test
    fun `authority strips path`() {
        assertEquals("abs.example.com", SourceUrl.parse("https://abs.example.com/audiobookshelf")!!.authority())
    }

    @Test
    fun `authority handles ipv4 with port`() {
        assertEquals("192.168.1.100:13378", SourceUrl.parse("http://192.168.1.100:13378")!!.authority())
    }

    @Test
    fun `two equal urls are equal`() {
        val a = SourceUrl.parse("https://abs.example.com")
        val b = SourceUrl.parse("https://abs.example.com")
        assertEquals(a, b)
    }
}
