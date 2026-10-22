package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.database.DictionaryPackEntity
import com.riffle.core.database.deleteRiffleDatabase
import com.riffle.core.database.openRiffleDatabase
import com.riffle.core.dictionary.DictionaryPackState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: IosPackStoreImpl was a no-op that always emitted NOT_INSTALLED / empty list.
 * After downloading a dict pack the UI showed no installed packs and the state never changed.
 *
 * These tests drive the real iOS code path: Room DAO backed by BundledSQLiteDriver.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPackStoreImplTest {

    private lateinit var databaseName: String
    private val tempFiles = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        databaseName = "riffle-pack-store-test-${NSUUID().UUIDString}.db"
    }

    @AfterTest
    fun cleanup() {
        runCatching { deleteRiffleDatabase(databaseName) }
        tempFiles.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private fun tempFileStore(): FileStore {
        val root = NSTemporaryDirectory() + "pack_store_test_" + NSUUID().UUIDString()
        tempFiles += root
        return object : FileStore {
            override fun resolve(namespace: String, relativePath: String): String {
                val base = "$root/$namespace"
                NSFileManager.defaultManager.createDirectoryAtPath(
                    base,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
                return if (relativePath.isEmpty()) base else "$base/$relativePath"
            }
        }
    }

    private fun entity(
        languageTag: String,
        state: DictionaryPackState = DictionaryPackState.INSTALLED,
    ) = DictionaryPackEntity(
        languageTag = languageTag,
        packVersion = "2024-01-01",
        installedAt = 1_700_000_000_000L,
        sizeBytes = 1024L,
        attributionHtml = "",
        licenseUrl = "",
        state = state.name,
    )

    @Test
    fun observePackState_returnsNotInstalled_whenDaoIsEmpty() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val store = IosPackStoreImpl(db.dictionaryPackDao(), tempFileStore())
            val state = store.observePackState("en").first()
            assertEquals(DictionaryPackState.NOT_INSTALLED, state)
        } finally {
            db.close()
        }
    }

    @Test
    fun observePackState_returnsInstalledState_afterUpsert() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val dao = db.dictionaryPackDao()
            dao.upsert(entity("en", DictionaryPackState.INSTALLED))
            val store = IosPackStoreImpl(dao, tempFileStore())
            val state = store.observePackState("en").first()
            assertEquals(DictionaryPackState.INSTALLED, state)
        } finally {
            db.close()
        }
    }

    @Test
    fun observeInstalledPacks_returnsEmpty_whenNoPacks() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val store = IosPackStoreImpl(db.dictionaryPackDao(), tempFileStore())
            val packs = store.observeInstalledPacks().first()
            assertTrue(packs.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun observeInstalledPacks_includesOnlyInstalledEntities() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val dao = db.dictionaryPackDao()
            dao.upsert(entity("en", DictionaryPackState.INSTALLED))
            dao.upsert(entity("fr", DictionaryPackState.DOWNLOADING))
            val store = IosPackStoreImpl(dao, tempFileStore())
            val packs = store.observeInstalledPacks().first()
            assertEquals(1, packs.size)
            assertEquals("en", packs.first().languageTag)
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteInstalledPack_removesFromDao() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val dao = db.dictionaryPackDao()
            dao.upsert(entity("en", DictionaryPackState.INSTALLED))
            val store = IosPackStoreImpl(dao, tempFileStore())
            store.deleteInstalledPack("en")
            val packs = store.observeInstalledPacks().first()
            assertTrue(packs.isEmpty(), "Pack should be removed from the store after delete")
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteInstalledPack_removesDbFile_ifPresent() = runTest {
        val db = openRiffleDatabase(databaseName)
        try {
            val fileStore = tempFileStore()
            val dictPath = fileStore.resolve("dicts", "en.db")
            NSFileManager.defaultManager.createFileAtPath(dictPath, contents = null, attributes = null)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(dictPath), "dict file should exist before delete")

            val dao = db.dictionaryPackDao()
            dao.upsert(entity("en"))
            val store = IosPackStoreImpl(dao, fileStore)
            store.deleteInstalledPack("en")

            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(dictPath), "dict file should be removed after delete")
        } finally {
            db.close()
        }
    }
}
