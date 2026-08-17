package com.riffle.core.common

/**
 * Typed seam for generating opaque random identities without coupling shared logic to a platform
 * UUID or ambient random-number implementation.
 */
fun interface RandomProvider {
    fun newId(): String
}
