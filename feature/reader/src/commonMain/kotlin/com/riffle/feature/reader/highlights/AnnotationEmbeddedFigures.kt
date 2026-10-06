package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.EmbeddedFigure
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val figuresJson = Json { ignoreUnknownKeys = true }
private val figuresSerializer = ListSerializer(EmbeddedFigure.serializer())

/**
 * Parses [AnnotationEntity.embeddedFigures]'s JSON column into domain [EmbeddedFigure]s.
 * Null/blank columns map to null.
 */
fun AnnotationEntity.decodedEmbeddedFigures(): List<EmbeddedFigure>? =
    embeddedFigures?.takeIf { it.isNotBlank() }?.let {
        figuresJson.decodeFromString(figuresSerializer, it)
    }
