package com.riffle.core.domain

/**
 * Where readaloud narration should resume, captured when the player was last closed: the reader page
 * ([href] + [progression]) and the sentence narrating at that moment ([fragmentRef], "href#fragmentId").
 * [progression] and [fragmentRef] are null when the close happened with no resolvable sentence/column.
 */
data class ReadaloudResumePosition(
    val href: String,
    val progression: Double?,
    val fragmentRef: String?,
)

/**
 * Persists the readaloud resume position per book so it survives leaving and re-entering the book (and
 * process death). Keyed by the reader's (serverId, itemId) — the same identity as the reading position —
 * because [ReadaloudResumePosition.href]/[ReadaloudResumePosition.fragmentRef] live in reader-locator
 * space. Device-local: unlike the reading position it is never synced to a server.
 */
interface ReadaloudResumeStore {
    suspend fun save(serverId: String, itemId: String, position: ReadaloudResumePosition)
    suspend fun load(serverId: String, itemId: String): ReadaloudResumePosition?
}
