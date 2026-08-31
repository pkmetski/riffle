package com.riffle.core.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    val driver = NativeSqliteDriver(IosRiffleDatabaseSchema, path)
    return IosRiffleDatabaseAccess(driver)
}
