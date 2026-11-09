package com.riffle.shared

import com.riffle.core.models.CrashReport

/**
 * iOS seam for presenting the system share sheet.
 *
 * Implemented in Swift ([IosShareSheetHelper]) so UIKit UIActivityViewController can be presented
 * without fighting Kotlin/Native's ObjC bindings for UIWindowScene enumeration. The Kotlin side
 * calls these lambdas with the platform-agnostic payload (file paths, text); the Swift side
 * wraps them into the NSURL/NSString items UIActivityViewController expects.
 */
interface IosShareSheetPresenter {
    /** Shares one or more files at the given [filePaths] with an optional [subject] line. */
    fun shareFiles(filePaths: List<String>, subject: String?)
    /** Shares a plain-text [text] string with an optional [subject] line. */
    fun shareText(text: String, subject: String?)
}
