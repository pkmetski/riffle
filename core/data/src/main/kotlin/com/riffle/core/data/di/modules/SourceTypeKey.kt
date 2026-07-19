package com.riffle.core.data.di.modules

import com.riffle.core.models.SourceType
import dagger.MapKey

/** Hilt map key for `Map<SourceType, CatalogFactory>` — see [CatalogModule]. */
@MapKey
annotation class SourceTypeKey(val value: SourceType)
