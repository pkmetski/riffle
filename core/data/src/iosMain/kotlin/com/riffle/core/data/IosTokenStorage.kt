package com.riffle.core.data

import com.riffle.core.domain.TokenStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
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
import platform.CoreFoundation.CFStringRef
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
    override suspend fun saveToken(sourceId: String, token: String) = saveKeychainItem(tokenAccount(sourceId), token)
    override suspend fun getToken(sourceId: String): String? = loadKeychainItem(tokenAccount(sourceId))
    override suspend fun deleteToken(sourceId: String) = deleteKeychainItem(tokenAccount(sourceId))
    override suspend fun savePassword(sourceId: String, password: String) = saveKeychainItem(passwordAccount(sourceId), password)
    override suspend fun getPassword(sourceId: String): String? = loadKeychainItem(passwordAccount(sourceId))
    override suspend fun deletePassword(sourceId: String) = deleteKeychainItem(passwordAccount(sourceId))
    private fun tokenAccount(sourceId: String) = "token:$sourceId"
    private fun passwordAccount(sourceId: String) = "password:$sourceId"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun saveKeychainItem(account: String, value: String) {
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    val dataRef = CFBridgingRetain(data) ?: return
    try {
        val exists = withKeychainQuery(account) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val out = alloc<ObjCObjectVar<Any?>>()
                val status = SecItemCopyMatching(query, out.ptr.reinterpret())
                status == errSecSuccess && out.value as? NSData != null
            }
        }
        if (exists) {
            withKeychainQuery(account) { query ->
                val attrs = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)!!
                CFDictionaryAddValue(attrs, kSecValueData, dataRef)
                SecItemUpdate(query, attrs)
                CFRelease(attrs)
            }
        } else {
            withKeychainQuery(account) { query ->
                CFDictionaryAddValue(query, kSecValueData, dataRef)
                SecItemAdd(query, null)
            }
        }
    } finally {
        CFRelease(dataRef)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun loadKeychainItem(account: String): String? = withKeychainQuery(account) { query ->
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
    val data: NSData? = memScoped {
        val out = alloc<ObjCObjectVar<Any?>>()
        val status = SecItemCopyMatching(query, out.ptr.reinterpret())
        if (status == errSecSuccess) out.value as? NSData else null
    }
    data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteKeychainItem(account: String) {
    withKeychainQuery(account) { query -> SecItemDelete(query) }
}

/**
 * CFDictionaryCreateMutable with null callbacks does not retain inserted values. All CFStringRefs
 * created here are kept alive for the duration of [block] and released in the finally clause,
 * preventing use-after-free when the dict holds raw pointers to them.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withKeychainQuery(account: String, block: (platform.CoreFoundation.CFMutableDictionaryRef) -> T): T {
    val svcRef: CFStringRef? = CFStringCreateWithCString(kCFAllocatorDefault, KEYCHAIN_SERVICE, kCFStringEncodingUTF8)
    val accRef: CFStringRef? = CFStringCreateWithCString(kCFAllocatorDefault, account, kCFStringEncodingUTF8)
    val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)!!
    CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
    if (svcRef != null) CFDictionaryAddValue(query, kSecAttrService, svcRef)
    if (accRef != null) CFDictionaryAddValue(query, kSecAttrAccount, accRef)
    try {
        return block(query)
    } finally {
        CFRelease(query)
        if (svcRef != null) CFRelease(svcRef)
        if (accRef != null) CFRelease(accRef)
    }
}
