package com.riffle.core.domain

import java.io.File

/** Launches Android's package installer for a downloaded APK. */
interface ApkInstaller {
    fun install(apk: File)
}
