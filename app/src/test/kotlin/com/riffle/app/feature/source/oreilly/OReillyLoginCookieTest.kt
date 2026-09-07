package com.riffle.app.feature.source.oreilly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OReillyLoginCookieTest {

    @Test
    fun `extracts orm-jwt from a multi-cookie header`() {
        val header = "sessionid=abc; orm-jwt=eyJHDR.PAYLOAD.SIG; groot-session=xyz"
        assertEquals("eyJHDR.PAYLOAD.SIG", parseOrmJwtFromCookieHeader(header))
    }

    @Test
    fun `extracts orm-jwt when it is the only cookie`() {
        assertEquals("tok123", parseOrmJwtFromCookieHeader("orm-jwt=tok123"))
    }

    @Test
    fun `returns null when orm-jwt absent, blank, or header null`() {
        assertNull(parseOrmJwtFromCookieHeader("sessionid=abc; other=1"))
        assertNull(parseOrmJwtFromCookieHeader("orm-jwt=; sessionid=abc"))
        assertNull(parseOrmJwtFromCookieHeader(""))
        assertNull(parseOrmJwtFromCookieHeader(null))
    }

    @Test
    fun `does not confuse a cookie whose name merely contains orm-jwt`() {
        // A cookie named "not-orm-jwt" must not match the "orm-jwt=" prefix check.
        assertNull(parseOrmJwtFromCookieHeader("not-orm-jwt=nope"))
    }
}
