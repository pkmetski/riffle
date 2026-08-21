package com.riffle.app.ui.source

import com.riffle.app.R
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSourceUiTest {
    @Test
    fun `Komga add-source form resolves through resources`() {
        val form = addSourceFormResources(SourceType.KOMGA, ServerType.AUDIOBOOKSHELF)!!

        assertEquals(R.string.ui_add_komga, form.addTitle)
        assertEquals(R.string.ui_edit_komga, form.editTitle)
        assertEquals(R.string.ui_source_url, form.urlLabel)
        assertEquals(R.string.ui_source_komga_help_text, form.helpText)
        assertEquals(R.string.ui_remove_source, form.removeLabel)
    }

    @Test
    fun `source picker blurbs resolve through resources`() {
        assertEquals(R.string.ui_source_abs_picker_blurb, sourcePickerBlurbRes(SourceType.ABS))
        assertEquals(R.string.ui_source_local_files_picker_blurb, sourcePickerBlurbRes(SourceType.LOCAL_FILES))
        assertEquals(R.string.ui_source_komga_picker_blurb, sourcePickerBlurbRes(SourceType.KOMGA))
    }

    @Test
    fun `local files has localized subtitle but no credentialed add-source copy`() {
        assertEquals(R.string.ui_source_local_files_subtitle, sourceSubtitleRes(SourceType.LOCAL_FILES))
        assertNull(addSourceFormResources(SourceType.LOCAL_FILES, ServerType.AUDIOBOOKSHELF))
    }
}
