package com.riffle.core.data

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

// Generic-password Keychain accessor shared by IosTokenStorage and IosEncryptedKeyValueStore —
// extracted unchanged from IosTokenStorage's original private helpers so both callers use the
// exact same tested add/update/query/delete sequence, just under different (service, account)
// namespacing.
internal const val KEYCHAIN_SERVICE = "com.riffle.app"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun saveKeychainItem(service: String, account: String, value: String) {
    val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
    val dataRef = CFBridgingRetain(data) ?: return
    try {
        val exists = withKeychainQuery(service, account) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val out = alloc<ObjCObjectVar<Any?>>()
                val status = SecItemCopyMatching(query, out.ptr.reinterpret())
                status == errSecSuccess && out.value as? NSData != null
            }
        }
        if (exists) {
            withKeychainQuery(service, account) { query ->
                val attrs = CFDictionaryCreateMutable(kCFAllocatorDefault, 1, null, null)!!
                CFDictionaryAddValue(attrs, kSecValueData, dataRef)
                SecItemUpdate(query, attrs)
                CFRelease(attrs)
            }
        } else {
            withKeychainQuery(service, account) { query ->
                CFDictionaryAddValue(query, kSecValueData, dataRef)
                SecItemAdd(query, null)
            }
        }
    } finally {
        CFRelease(dataRef)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun loadKeychainItem(service: String, account: String): String? = withKeychainQuery(service, account) { query ->
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
internal fun deleteKeychainItem(service: String, account: String) {
    withKeychainQuery(service, account) { query -> SecItemDelete(query) }
}

/**
 * CFDictionaryCreateMutable with null callbacks does not retain inserted values. All CFStringRefs
 * created here are kept alive for the duration of [block] and released in the finally clause,
 * preventing use-after-free when the dict holds raw pointers to them.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> withKeychainQuery(service: String, account: String, block: (platform.CoreFoundation.CFMutableDictionaryRef) -> T): T {
    val svcRef: CFStringRef? = CFStringCreateWithCString(kCFAllocatorDefault, service, kCFStringEncodingUTF8)
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
