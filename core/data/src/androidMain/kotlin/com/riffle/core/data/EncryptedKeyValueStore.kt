package com.riffle.core.data

// Moved to core:common in Phase 6. Typealias kept so any remaining same-package references
// still resolve; all explicit imports have been updated to com.riffle.core.common.EncryptedKeyValueStore.
@Deprecated("Use com.riffle.core.common.EncryptedKeyValueStore", ReplaceWith("com.riffle.core.common.EncryptedKeyValueStore"))
typealias EncryptedKeyValueStore = com.riffle.core.common.EncryptedKeyValueStore
