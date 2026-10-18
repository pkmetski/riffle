package com.riffle.core.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

abstract class DaoTestBase {

    protected lateinit var db: RiffleDatabaseAccess

    @BeforeTest
    fun openDatabase() {
        val roomDb = Room.inMemoryDatabaseBuilder<RiffleDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()
        db = DefaultRiffleDatabaseAccess(roomDb)
    }

    @AfterTest
    fun closeDatabase() {
        db.close()
    }

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
