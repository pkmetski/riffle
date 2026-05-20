package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `https url is accepted`() {
        val url = ServerUrl.parse("https://abs.example.com")
        assertEquals("https://abs.example.com", url?.value)
    }

    @Test
    fun `http url is accepted`() {
        val url = ServerUrl.parse("http://192.168.1.100:13378")
        assertEquals("http://192.168.1.100:13378", url?.value)
    }

    @Test
    fun `trailing slash is stripped`() {
        val url = ServerUrl.parse("https://abs.example.com/")
        assertEquals("https://abs.example.com", url?.value)
    }

    @Test
    fun `multiple trailing slashes are stripped`() {
        val url = ServerUrl.parse("https://abs.example.com///")
        assertEquals("https://abs.example.com", url?.value)
    }

    @Test
    fun `empty string returns null`() {
        assertNull(ServerUrl.parse(""))
    }

    @Test
    fun `blank string returns null`() {
        assertNull(ServerUrl.parse("   "))
    }

    @Test
    fun `url without scheme returns null`() {
        assertNull(ServerUrl.parse("abs.example.com"))
    }

    @Test
    fun `ftp scheme returns null`() {
        assertNull(ServerUrl.parse("ftp://abs.example.com"))
    }

    @Test
    fun `url with path prefix is preserved`() {
        val url = ServerUrl.parse("https://abs.example.com/audiobookshelf")
        assertEquals("https://abs.example.com/audiobookshelf", url?.value)
    }

    @Test
    fun `two equal urls are equal`() {
        val a = ServerUrl.parse("https://abs.example.com")
        val b = ServerUrl.parse("https://abs.example.com")
        assertEquals(a, b)
    }
}
