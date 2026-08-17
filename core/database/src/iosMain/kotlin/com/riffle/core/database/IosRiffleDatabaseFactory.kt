package com.riffle.core.database

import androidx.room.Room

fun openRiffleDatabase(path: String): RiffleDatabaseAccess =
    Room.databaseBuilder<RiffleDatabase>(name = path)
        .buildRiffleDatabase()
