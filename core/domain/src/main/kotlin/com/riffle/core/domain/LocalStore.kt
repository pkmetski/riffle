package com.riffle.core.domain

import java.io.File
import java.io.InputStream

interface LocalStore {
    fun get(itemId: String): File?
    suspend fun save(itemId: String, stream: InputStream): File
    fun delete(itemId: String)
    fun clear()
    fun listItemIds(): List<String>
}
