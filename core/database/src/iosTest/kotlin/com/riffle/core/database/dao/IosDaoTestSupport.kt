package com.riffle.core.database.dao

import co.touchlab.sqliter.DatabaseFileContext
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SourceEntity
import com.riffle.core.database.openRiffleDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Shared fixture for the iOS DAO suites.
 *
 * These tests drive the real `Ios*Dao` implementations through the real
 * [openRiffleDatabase] entry point — the same NativeSqliteDriver + [com.riffle.core.database.IosRiffleDatabaseSchema]
 * pair production uses — so the assertions cover the actual SQL each DAO issues, not a fake.
 *
 * Going through [openRiffleDatabase] rather than constructing DAOs against a bare driver (as
 * `IosRiffleDatabaseSchemaTest` does) is deliberate: every DAO then shares the one
 * [com.riffle.core.database.IosInvalidator] instance that `IosRiffleDatabaseAccess` owns, which is
 * what makes the Flow-emission assertions meaningful. A DAO that forgets to call `invalidate()`
 * after a write leaves live collectors stale on device; that is exactly the defect these suites pin.
 */
abstract class IosDaoTestBase {

    protected lateinit var db: RiffleDatabaseAccess

    private lateinit var databaseName: String

    @BeforeTest
    fun openDatabase() {
        // A bare filename, exactly as production passes it (`openRiffleDatabase("riffle.db")` in
        // IosDatabaseKoinModule). SQLiter treats the argument as a NAME and resolves it against its
        // own base path; handing it a full path throws "File … contains a path separator" out of
        // DatabaseConfiguration's checkFilename.
        databaseName = "riffle-dao-test-${NSUUID().UUIDString}.db"
        db = openRiffleDatabase(databaseName)
    }

    @AfterTest
    fun closeDatabase() {
        db.close()
        DatabaseFileContext.deleteDatabase(databaseName)
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
