package com.riffle.core.common

import platform.Foundation.NSUUID

/**
 * iOS production identity generator, the `NSUUID` twin of `jvmMain`'s [SystemRandomProvider]
 * (`java.util.UUID` has no Kotlin/Native equivalent). Both emit the canonical 8-4-4-4-12 form, so
 * ids minted on either platform are interchangeable in the sync ledgers that carry them.
 */
object IosRandomProvider : RandomProvider {
    override fun newId(): String = NSUUID().UUIDString()
}
