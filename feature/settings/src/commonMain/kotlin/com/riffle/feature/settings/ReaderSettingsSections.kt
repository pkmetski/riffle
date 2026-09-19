package com.riffle.feature.settings

import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme

/**
 * The show/hide and enabled/disabled decisions the reader-settings Display section makes.
 *
 * They used to be inline `if` conditions inside the Android composable, which meant the only way
 * to pin them was an instrumentation test and the iOS settings surface had to re-derive them.
 * Pulling them out keeps one rule per decision that either platform can call and either platform's
 * test suite can assert.
 */
object ReaderSettingsSections {

    /**
     * The "View" header only earns its place when at least one of the controls beneath it renders;
     * a PDF supports neither, so the bare header must not be left stranded.
     */
    fun showsViewSectionHeader(capabilities: RenderCapabilities): Boolean =
        capabilities.supportsReadingModeSwitch || capabilities.supportsDoublePage

    /**
     * Double-page has a rendering effect when paginated is the base mode, or when
     * `forcePaginatedInLandscape` will promote to paginated in landscape. Otherwise the toggle
     * stays visible but inert so its state is still discoverable.
     */
    fun doublePageToggleEnabled(orientation: ReaderOrientation, forcePaginatedInLandscape: Boolean): Boolean =
        orientation == ReaderOrientation.Horizontal || forcePaginatedInLandscape

    /** The Auto sub-controls only appear once Auto is the selected theme. */
    fun showsAutoThemeBlock(theme: ReaderTheme): Boolean = theme == ReaderTheme.Auto

    /**
     * Inside the Auto block: the Settings host may edit the schedule, while the reader's own sheet
     * shows a read-only summary card instead (editing lives in Settings → Display).
     */
    fun showsAutoThemeEditor(theme: ReaderTheme, scheduleEditable: Boolean): Boolean =
        showsAutoThemeBlock(theme) && scheduleEditable

    /**
     * The day/night schedule rows only belong to the Schedule mode — following the app theme has
     * no times to pick, so the editor collapses to the light/dark theme pairing.
     */
    fun showsScheduleEditor(autoMode: AutoReaderThemeMode): Boolean =
        autoMode == AutoReaderThemeMode.Schedule

    /** "Colored chapter map" is a sub-setting of the chapter map and greys out when it is off. */
    fun coloredChapterMapEnabled(showChapterMap: Boolean): Boolean = showChapterMap
}
