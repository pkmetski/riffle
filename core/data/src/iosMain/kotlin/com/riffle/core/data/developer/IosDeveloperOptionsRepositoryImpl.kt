package com.riffle.core.data.developer

import com.riffle.core.domain.developer.DeveloperOptionsRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
private object Keychain {
    private const val SERVICE = "com.riffle.github_pat"
    private const val ACCOUNT = "github_pat"

    fun get(): String? = memScoped {
        val query = NSMutableDictionary()
        query.setValue(kSecClassGenericPassword, kSecClass)
        query.setValue(SERVICE, kSecAttrService)
        query.setValue(ACCOUNT, kSecAttrAccount)
        query.setValue(true, kSecReturnData)
        query.setValue(kSecMatchLimitOne, kSecMatchLimit)
        val result = alloc<ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query, result.ptr.reinterpret())
        if (status != errSecSuccess) return null
        val data = result.value as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    fun set(value: String?) {
        val query = NSMutableDictionary()
        query.setValue(kSecClassGenericPassword, kSecClass)
        query.setValue(SERVICE, kSecAttrService)
        query.setValue(ACCOUNT, kSecAttrAccount)
        SecItemDelete(query)
        if (value.isNullOrEmpty()) return
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val attrs = NSMutableDictionary()
        attrs.setValue(kSecClassGenericPassword, kSecClass)
        attrs.setValue(SERVICE, kSecAttrService)
        attrs.setValue(ACCOUNT, kSecAttrAccount)
        attrs.setValue(data, kSecValueData)
        SecItemAdd(attrs, null)
    }
}

internal class IosDeveloperOptionsRepositoryImpl : DeveloperOptionsRepository {
    private val defaults = NSUserDefaults.standardUserDefaults

    init {
        // One-time migration: move PAT from NSUserDefaults to Keychain
        val existingPat = defaults.stringForKey(KEY_GITHUB_PAT)?.takeIf { it.isNotEmpty() }
        if (existingPat != null && Keychain.get() == null) {
            Keychain.set(existingPat)
            defaults.removeObjectForKey(KEY_GITHUB_PAT)
        }
    }

    private val _developerModeEnabled = MutableStateFlow(
        if (defaults.objectForKey(KEY_DEV_MODE) != null) defaults.boolForKey(KEY_DEV_MODE) else false,
    )

    override val developerModeEnabled: Flow<Boolean> = _developerModeEnabled

    override suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = KEY_DEV_MODE)
        _developerModeEnabled.value = enabled
    }

    override suspend fun getGithubPat(): String? = Keychain.get()

    override suspend fun setGithubPat(pat: String?) {
        Keychain.set(pat)
    }

    private companion object {
        const val KEY_DEV_MODE = "developer.mode_enabled"
        const val KEY_GITHUB_PAT = "developer.github_pat"
    }
}
