package com.riffle.app.feature.settings.debug

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import com.riffle.core.logging.InMemoryLogBuffer
import com.riffle.core.logging.LogChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Backs [DebugLogScreen]. Owns no state of its own — the [InMemoryLogBuffer] is the source of truth
 * and its [StateFlow] drives the UI directly. The VM exists to give the Compose screen a stable
 * Hilt-injected handle to the singleton buffer and to package the "share as .txt" workflow.
 */
@HiltViewModel
class DebugLogViewModel @Inject constructor(
    application: Application,
    private val buffer: InMemoryLogBuffer,
) : AndroidViewModel(application) {

    val entries: StateFlow<List<InMemoryLogBuffer.Entry>> = buffer.entries

    fun clear() = buffer.clear()

    /**
     * Write the current buffer snapshot (optionally filtered to [channels]) to a temp file in
     * `filesDir/debug_logs/`, return a share [Intent] pointing at it via FileProvider. The share
     * sheet lets the user email / message the file — no adb required.
     *
     * Returns null when the buffer is empty (nothing to share).
     */
    fun buildShareIntent(channels: Set<LogChannel>?): Intent? {
        val entries = buffer.snapshot()
            .let { all -> if (channels.isNullOrEmpty()) all else all.filter { it.channel in channels } }
        if (entries.isEmpty()) return null

        val app = getApplication<Application>()
        val dir = File(app.filesDir, "debug_logs").apply { mkdirs() }
        // Reuse one filename so successive shares overwrite instead of piling on disk. The
        // timestamp still appears in the file contents (per-entry) and in the share subject,
        // so the recipient can still tell captures apart.
        dir.listFiles { f -> f.isFile && f.name.startsWith("riffle-debug-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "riffle-debug-$stamp.txt")
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        file.bufferedWriter().use { w ->
            entries.forEach { e ->
                w.append(fmt.format(Date(e.timestampMs)))
                w.append(' ').append(e.level.name)
                w.append(" [").append(e.channel.tag).append("] ")
                w.append(e.message)
                e.throwableSummary?.let { w.append(" | ").append(it) }
                w.newLine()
            }
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Riffle debug log ($stamp)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
