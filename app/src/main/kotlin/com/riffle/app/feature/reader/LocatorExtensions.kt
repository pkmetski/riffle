package com.riffle.app.feature.reader

import com.riffle.feature.reader.ReadaloudPosition
import org.readium.r2.shared.publication.Locator

fun Locator.toReadaloudPosition() = ReadaloudPosition(
    href = href.toString(),
    progression = locations.progression,
    locatorJson = toJSON().toString(),
)
