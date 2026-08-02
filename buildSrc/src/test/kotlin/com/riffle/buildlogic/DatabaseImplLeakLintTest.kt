package com.riffle.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseImplLeakLintTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.root
    }

    private fun writeKt(relative: String, body: String) {
        root.resolve(relative).apply {
            parentFile.mkdirs()
            writeText(body)
        }
    }

    @Test
    fun `flags concrete database imports in production and test sources`() {
        writeKt(
            "core/data/src/main/kotlin/DatabaseModule.kt",
            "import com.riffle.core.database.RiffleDatabase\n",
        )
        writeKt(
            "app/src/androidTest/kotlin/DatabaseTest.kt",
            "import com.riffle.core.database.RiffleDatabase\n",
        )

        assertEquals(2, DatabaseImplLeakLint.findDatabaseImplLeaks(root).size)
    }

    @Test
    fun `flags wildcard database imports because they expose the implementation`() {
        writeKt("app/src/main/kotlin/App.kt", "import com.riffle.core.database.*\n")

        assertEquals(1, DatabaseImplLeakLint.findDatabaseImplLeaks(root).size)
    }

    @Test
    fun `flags Room and SQLite implementation imports outside database modules`() {
        writeKt("core/data/src/main/kotlin/RoomStore.kt", "import androidx.room.Room\n")
        writeKt("app/src/main/kotlin/SqlStore.kt", "import androidx.sqlite.SQLiteConnection\n")

        assertEquals(2, DatabaseImplLeakLint.findDatabaseImplLeaks(root).size)
    }

    @Test
    fun `allows implementation imports inside core database`() {
        writeKt(
            "core/database/src/androidDeviceTest/kotlin/MigrationTest.kt",
            "import com.riffle.core.database.RiffleDatabase\n",
        )

        assertTrue(DatabaseImplLeakLint.findDatabaseImplLeaks(root).isEmpty())
    }

    @Test
    fun `allows Room annotations inside core database api`() {
        writeKt(
            "core/database-api/src/commonMain/kotlin/SourceEntity.kt",
            "import androidx.room.Entity\n",
        )

        assertTrue(DatabaseImplLeakLint.findDatabaseImplLeaks(root).isEmpty())
    }

    @Test
    fun `allows the platform neutral database access contract`() {
        writeKt(
            "core/data/src/main/kotlin/DatabaseModule.kt",
            "import com.riffle.core.database.RiffleDatabaseAccess\n",
        )

        assertTrue(DatabaseImplLeakLint.findDatabaseImplLeaks(root).isEmpty())
    }

    @Test
    fun `allows DAO and entity imports outside database modules`() {
        writeKt(
            "core/data/src/main/kotlin/SourceStore.kt",
            "import com.riffle.core.database.SourceDao\n",
        )

        assertTrue(DatabaseImplLeakLint.findDatabaseImplLeaks(root).isEmpty())
    }
}
