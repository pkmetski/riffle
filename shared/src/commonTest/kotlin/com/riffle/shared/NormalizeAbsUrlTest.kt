package com.riffle.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizeAbsUrlTest {

    @Test
    fun schemelessUrlGetsHttpsPrefix() {
        assertEquals("https://abs.example.com", normalizeAbsUrl("abs.example.com"))
    }

    @Test
    fun httpUrlPassesThrough() {
        assertEquals("http://abs.local:13378", normalizeAbsUrl("http://abs.local:13378"))
    }

    @Test
    fun httpsUrlPassesThrough() {
        assertEquals("https://abs.example.com", normalizeAbsUrl("https://abs.example.com"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsStripped() {
        assertEquals("https://abs.example.com", normalizeAbsUrl("  abs.example.com  "))
    }

    @Test
    fun urlWithPortAndPathPassesThrough() {
        assertEquals("http://192.168.1.1:13378", normalizeAbsUrl("http://192.168.1.1:13378"))
    }

    @Test
    fun uppercaseSchemePassesThrough() {
        assertEquals("HTTP://abs.local", normalizeAbsUrl("HTTP://abs.local"))
    }
}
