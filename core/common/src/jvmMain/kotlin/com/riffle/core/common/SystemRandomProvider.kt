package com.riffle.core.common

import java.util.UUID

/** JVM production identity generator. Platform hosts can bind their own implementation. */
object SystemRandomProvider : RandomProvider {
    override fun newId(): String = UUID.randomUUID().toString()
}
