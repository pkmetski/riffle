package com.riffle.app.feature.reader.session

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation

fun makeAnnotation(
    id: String = "a1",
    type: String = AnnotationEntity.TYPE_HIGHLIGHT,
    cfi: String = "epubcfi(/6/4!/4/2)",
    color: String = "yellow",
) = Annotation(
    id = id,
    sourceId = "srv1",
    itemId = "item1",
    type = type,
    cfi = cfi,
    color = color,
    note = null,
    textSnippet = "Hello world",
    textBefore = "Some text before",
    textAfter = "Some text after",
    chapterHref = "chapter1.xhtml",
    spineIndex = 0,
    progression = 0.3,
    bookmarkTitle = "",
    createdAt = 1000L,
    updatedAt = 1001L,
)
