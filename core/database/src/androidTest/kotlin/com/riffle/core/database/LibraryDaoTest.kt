package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.libraryDao()
        // libraries FK-references servers; seed the owning Servers first.
        runBlocking {
            db.serverDao().upsert(ServerEntity("s1", "http://s1", isActive = true, insecureConnectionAllowed = false, username = "u"))
            db.serverDao().upsert(ServerEntity("s2", "http://s2", isActive = false, insecureConnectionAllowed = false, username = "u"))
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun library(id: String, serverId: String, name: String = "Library $id", mediaType: String = "book") =
        LibraryEntity(id = id, name = name, mediaType = mediaType, serverId = serverId)

    // Two Servers pointing at the same Audiobookshelf instance (issue #113) emit identical
    // library ids — they are unique only within an instance. Both rows must coexist, each owned
    // by its own Server.
    @Test
    fun sameLibraryIdOnDifferentServersBothPersist() = runTest {
        dao.replaceAllForServer("s1", listOf(library("lib", "s1", name = "From s1")))
        dao.replaceAllForServer("s2", listOf(library("lib", "s2", name = "From s2")))

        val s1Libs = dao.observeByServerId("s1").first()
        val s2Libs = dao.observeByServerId("s2").first()

        assertEquals(listOf("From s1"), s1Libs.map { it.name })
        assertEquals(listOf("From s2"), s2Libs.map { it.name })
    }

    // replaceAllForServer must scope its delete+upsert to the target Server only — refreshing one
    // Server's libraries must not steal a same-id library away from another Server.
    @Test
    fun replaceAllForServerLeavesOtherServersSameIdLibraryIntact() = runTest {
        dao.replaceAllForServer("s1", listOf(library("lib", "s1")))
        dao.replaceAllForServer("s2", listOf(library("lib", "s2")))

        // Re-refresh s1 (as refreshLibraries does for the active Server).
        dao.replaceAllForServer("s1", listOf(library("lib", "s1")))

        assertEquals(1, dao.observeByServerId("s1").first().size)
        assertEquals(1, dao.observeByServerId("s2").first().size)
    }
}
