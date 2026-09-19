package com.riffle.core.data.localfiles

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS counterpart to Android's SAF persisted-grant health check (issue #1065). The previous stub
 * answered "healthy" for everything, so a folder that had been moved or unmounted still looked
 * rescannable in Settings.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesFolderHealthCheckerTest {

    private val created = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        created.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private fun newDirectory(): String {
        val path = NSTemporaryDirectory() + "health_" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        created += path
        return path
    }

    private fun newFile(): String {
        val path = NSTemporaryDirectory() + "health_file_" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)
        created += path
        return path
    }

    @Test
    fun `an existing readable directory is healthy`() {
        assertTrue(IosLocalFilesFolderHealthChecker().isHealthy(newDirectory()))
    }

    @Test
    fun `a missing directory is unhealthy`() {
        assertFalse(IosLocalFilesFolderHealthChecker().isHealthy(NSTemporaryDirectory() + "absent_" + NSUUID().UUIDString()))
    }

    @Test
    fun `a deleted directory stops being healthy`() {
        val path = newDirectory()
        val checker = IosLocalFilesFolderHealthChecker()
        assertTrue(checker.isHealthy(path), "precondition: healthy before removal")

        NSFileManager.defaultManager.removeItemAtPath(path, error = null)

        assertFalse(checker.isHealthy(path), "a folder that vanished must report unhealthy")
    }

    @Test
    fun `a plain file is not a healthy folder`() {
        assertFalse(IosLocalFilesFolderHealthChecker().isHealthy(newFile()))
    }

    @Test
    fun `healthFor reports each folder independently`() {
        val present = newDirectory()
        val missing = NSTemporaryDirectory() + "absent_" + NSUUID().UUIDString()

        val health = IosLocalFilesFolderHealthChecker().healthFor(listOf(present, missing))

        assertEquals(mapOf(present to true, missing to false), health)
    }

    @Test
    fun `healthFor returns an empty map for no folders`() {
        assertEquals(emptyMap(), IosLocalFilesFolderHealthChecker().healthFor(emptyList()))
    }
}
