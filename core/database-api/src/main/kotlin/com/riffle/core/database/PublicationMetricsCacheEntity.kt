package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "publication_metrics_cache",
    primaryKeys = ["sourceId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class PublicationMetricsCacheEntity(
    val sourceId: String,
    val itemId: String,
    val ebookFileIno: String,
    val totalPositions: Int?,
    val pageCount: Int?,
    val cachedAt: Long,
)
