package com.riffle.core.data.di

import com.riffle.core.common.FileStore
import com.riffle.core.data.IosConnectivityObserver
import com.riffle.core.data.IosDeviceLabelResolver
import com.riffle.core.data.IosFileStore
import com.riffle.core.data.IosTokenStorage
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.openRiffleDatabase
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.TokenStorage
import org.koin.dsl.module
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

val iosDataModule = module {
    single<ConnectivityObserver> { IosConnectivityObserver() }
    single<TokenStorage> { IosTokenStorage() }
    single<FileStore> { IosFileStore() }
    single<DeviceLabelResolver> { IosDeviceLabelResolver() }
    single<RiffleDatabaseAccess> {
        val documentsDir = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true,
        ).first() as String
        openRiffleDatabase("$documentsDir/riffle.db")
    }
}
