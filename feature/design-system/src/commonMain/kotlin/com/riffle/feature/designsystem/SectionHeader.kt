package com.riffle.feature.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Padding Android's library section headers have always used; the canonical rhythm. */
private val SectionHeaderPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)

/**
 * The heading that opens a list section — a library shelf, a downloads group, a browse section.
 *
 * There were **five** of these: `app/.../library/LibraryItemsScreen.kt` (plain title),
 * `app/.../downloads/DownloadsScreen.kt` (title · total + a trailing action),
 * `shared/.../library/LibraryItemsScreen.kt` (title + a "See all" link),
 * `shared/.../downloads/DownloadsScreen.kt` (plain title) and `shared/.../settings/SettingsScreen.kt`
 * (a different concept — see [SettingsSectionHeader]). They disagreed about padding, about the
 * typography, and about whether the trailing affordance was a `TextButton` or a tinted `Text`.
 *
 * This is the union of the four list headers. Android's padding and `titleMedium` weight win
 * because they are what ships; the trailing affordance is Android's `TextButton`, which is the
 * one with a real touch target. [totalLabel] renders as Android's `"$title · $totalLabel"`.
 *
 * [tag] sets a stable `testTag`, which XCUITest reads as the element's `accessibilityIdentifier`.
 * Pass a locale-independent key (`"section-header-IN_PROGRESS"`) so a harness that locates a
 * section does not have to match the translated title.
 *
 * [contentPadding] is overridable for the one caller that already pads its own column (the
 * Downloads screen), exactly as `CacheSettingsRow` does for the same reason.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    totalLabel: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tag: String? = null,
    contentPadding: PaddingValues = SectionHeaderPadding,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (totalLabel != null) "$title · $totalLabel" else title,
            style = MaterialTheme.typography.titleMedium,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The coloured header + divider pair that opens every top-level section on the Settings screen —
 * a different concept from [SectionHeader] and deliberately a different composable, exactly as
 * Android has always had both.
 *
 * Android's `SettingsSectionHeader` is the canonical rendering. iOS's copy was a `BasicText` at a
 * hardcoded `13.sp` in a hardcoded `Color(0xFF1565C0)` with no divider: `BasicText` takes neither
 * colour nor style from `MaterialTheme`, so that header was the same blue in dark mode as in
 * light, which is exactly the class of drift this module exists to end.
 */
@Composable
fun SettingsSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}
