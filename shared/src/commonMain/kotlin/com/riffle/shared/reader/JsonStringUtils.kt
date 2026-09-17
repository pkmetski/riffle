package com.riffle.shared.reader

/**
 * Escapes a string for safe embedding inside a JSON string literal (between the surrounding
 * double-quotes).  Handles the four characters that would otherwise break the JSON parser:
 * backslash, double-quote, newline, and carriage-return.
 *
 * This is the canonical Kotlin implementation.  The Swift side carries a mirror in
 * ReadiumEpubNavigatorBridge.swift (`var jsonEscaped: String`) that is kept separate because
 * it operates on Readium-Swift types that never cross the KMP boundary — any change to the
 * escaping logic must be applied to both sides.
 *
 * Not a full JSON serialiser — use a proper library for arbitrary values.  Purpose-built for
 * the slim hand-rolled JSON that the bridge protocol uses for locator/decoration/TOC wires.
 */
internal fun String.jsonEscaped(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
