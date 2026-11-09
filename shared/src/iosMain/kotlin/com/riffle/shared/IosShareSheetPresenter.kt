package com.riffle.shared

interface IosShareSheetPresenter {
    /** Shares one or more files at the given [filePaths] with an optional [subject] line. */
    fun shareFiles(filePaths: List<String>, subject: String?)

    /** Shares a plain-text [text] string with an optional [subject] line. */
    fun shareText(text: String, subject: String?)
}
