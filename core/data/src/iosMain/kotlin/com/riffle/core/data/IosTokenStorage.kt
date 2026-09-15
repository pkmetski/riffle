package com.riffle.core.data

import com.riffle.core.domain.TokenStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import kotlinx.cinterop.ObjCObjectVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "com.riffle.app"

class IosTokenStorage : TokenStorage {

    override suspend fun saveToken(sourceId: String, token: String) =
        saveKeychainItem(tokenAccount(sourceId), token)

    override suspend fun getToken(sourceId: String): String? =
        loadKeychainItem(tokenAccount(sourceId))

    override suspend fun deleteToken(sourceId: String) =
        deleteKeychainItem(tokenAccount(sourceId))

    override suspend fun savePassword(sourceId: String, password: String) =
        saveKeychainItem(passwordAccount(sourceId), password)

    override suspend fun getPassword(sourceId: String): String? =
        loadKeychainItem(passwordAccount(sourceId))

    override suspend fun deletePassword(sourceId: String) =
        deleteKeychainItem(passwordAccount(sourceId))

    private fun tokenAccount(sourceId: String) = "token:$sourceId"
    private fun passwordAccount(sourceId: String) = "password:$sourceId"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun saveKeychainItem(account: String, value: String) {
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    val dataRef = CFBridgingRetain(data) ?: return
    val exists = loadKeychainData(account) != null
    if (exists) {
        val query = keychainQuery(account)
        val attrs = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)!!
        CFDictionaryAddValue(attrs, kSecValueData, dataRef)
        SecItemUpdate(query, attrs)
        CFRelease(attrs)
        CFRelease(query)
    } else {
        val query = keychainQuery(account)
        CFDictionaryAddValue(query, kSecValueData, dataRef)
        SecItemAdd(query, null)
        CFRelease(query)
    }
    CFRelease(dataRef)
}

@OptIn(ExperimentalForeignApi::class)
private fun loadKeychainItem(account: String): String? {
    val data = loadKeychainData(account) ?: return null
    return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun loadKeychainData(account: String): NSData? {
    val query = keychainQuery(account)
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
    return memScoped {
        val result = alloc<ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query, result.ptr.reinterpret())
        CFRelease(query)
        if (status == errSecSuccess) result.value as? NSData else null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteKeychainItem(account: String) {
    val query = keychainQuery(account)
    SecItemDelete(query)
    CFRelease(query)
}

@OptIn(ExperimentalForeignApi::class)
private fun keychainQuery(account: String): platform.CoreFoundation.CFMutableDictionaryRef {
    val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)!!
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    cfString(KEYCHAIN_SERVICE) { svc -> CFDictionaryAddValue(query, kSecAttrService, svc) }
    cfString(account) { acc -> CFDictionaryAddValue(query, kSecAttrAccount, acc) }
    return query
}

@OptIn(ExperimentalForeignApi::class)
private inline fun cfString(s: String, block: (platform.CoreFoundation.CFStringRef) -> Unit) {
    val ref = CFStringCreateWithCString(kCFAllocatorDefault, s, kCFStringEncodingUTF8) ?: return
    block(ref)
    CFRelease(ref)
}
