package com.riffle.core.data

import android.content.Context
import com.riffle.core.common.FileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * [FileStore] implementation that roots all namespaces under [Context.getFilesDir].
 *
 * Directory layout: `<filesDir>/<namespace>/<relativePath>`
 */
class FilesdirFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : FileStore {

    override fun resolve(namespace: String, relativePath: String): String {
        val base = File(context.filesDir, namespace)
        return if (relativePath.isEmpty()) {
            base.apply { mkdirs() }.absolutePath
        } else {
            File(base, relativePath).also { it.parentFile?.mkdirs() }.absolutePath
        }
    }
}
