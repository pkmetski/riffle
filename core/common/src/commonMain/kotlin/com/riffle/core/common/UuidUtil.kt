package com.riffle.core.common

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun randomUuidString(): String = Uuid.random().toString()
