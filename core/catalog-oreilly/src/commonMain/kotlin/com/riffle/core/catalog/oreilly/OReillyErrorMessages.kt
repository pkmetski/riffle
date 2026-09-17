package com.riffle.core.catalog.oreilly

fun oReillyFriendlyErrorMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it::class.simpleName?.contains("UnknownHostException") == true } ->
            "You appear to be offline. Connect to the internet and try again."
        chain.any { it::class.simpleName?.endsWith("IOException") == true } ->
            "Couldn't reach O'Reilly. Check your connection and try again."
        chain.any { it is OReillyHttpException } ->
            "Couldn't reach O'Reilly. Check your connection and try again."
        else -> t.message ?: t::class.simpleName ?: "Error"
    }
}
