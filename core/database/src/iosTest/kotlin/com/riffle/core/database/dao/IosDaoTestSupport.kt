package com.riffle.core.database.dao

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import com.riffle.core.database.DefaultRiffleDatabaseAccess
import com.riffle.core.database.RiffleDatabase
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Shared fixture for the iOS DAO suites.
 *
 * Creates an in-memory Room database backed by the system SQLite (NativeSQLiteDriver) — the same
 * driver production uses — so the assertions cover the real Room-generated DAO SQL, not a fake.
 * In-memory databases are automatically destroyed when closed, which keeps the test runner's
 * file system clean without any explicit cleanup.
 */
abstract class IosDaoTestBase {

    protected lateinit var db: RiffleDatabaseAccess

    @BeforeTest
    fun openDatabase() {
        val roomDb = Room.inMemoryDatabaseBuilder<RiffleDatabase>()
            .setDriver(NativeSQLiteDriver())
            // Dispatchers.Unconfined makes Room re-execute queries on the calling coroutine's
            // thread (the test thread) instead of a real background pool. This lets the test
            // scheduler control flow-emission timing without real thread delays.
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db = DefaultRiffleDatabaseAccess(roomDb)
    }

    @AfterTest
    fun closeDatabase() {
        db.close()
    }

    /**
     * Seeds a `sources` row. Most of the tables these DAOs own carry a `sourceId` foreign key to
     * `sources(id)`, so production always has the parent row in place; the fixtures mirror that.
     */
    protected suspend fun seedSource(id: String) {
        db.sourceDao().upsert(
            SourceEntity(
                id = id,
                url = "https://$id.example.invalid",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "reader",
            ),
        )
    }

    protected companion object {
        const val SOURCE_ID = "source-1"
        const val OTHER_SOURCE_ID = "source-2"
        const val ITEM_ID = "item-1"
        const val OTHER_ITEM_ID = "item-2"
    }
}

/**
 * Subscribes to [flow] for the remainder of the test and returns the live list of everything it
 * emits. The list is mutated in place as emissions arrive, so a caller can assert on `size` before
 * and after a write to prove that the write actually pushed a new value to an *existing*
 * subscriber.
 *
 * The collector runs on an [UnconfinedTestDispatcher] so it starts eagerly and resumes inline;
 * [settleEmissions] drains anything the flow operators still have queued. `backgroundScope` means
 * `runTest` cancels the collector at the end of the test without any explicit bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.recordEmissions(flow: Flow<T>): List<T> {
    val recorded = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        flow.collect { recorded.add(it) }
    }
    testScheduler.runCurrent()
    return recorded
}

/** Drains pending coroutine work so a [recordEmissions] list is up to date before asserting. */
fun TestScope.settleEmissions() {
    testScheduler.runCurrent()
}
