package com.riffle.feature.source.ui

import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_add_komga
import com.riffle.feature.source.ui.generated.resources.ui_edit_komga
import com.riffle.feature.source.ui.generated.resources.ui_remove_source
import com.riffle.feature.source.ui.generated.resources.ui_source_abs_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_komga_help_text
import com.riffle.feature.source.ui.generated.resources.ui_source_komga_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_local_files_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_local_files_subtitle
import com.riffle.feature.source.ui.generated.resources.ui_source_url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSourceUiTest {
    @Test
    fun `Komga add-source form resolves through resources`() {
        val form = addSourceFormResources(SourceType.KOMGA, ServerType.AUDIOBOOKSHELF)!!

        assertEquals(Res.string.ui_add_komga, form.addTitle)
        assertEquals(Res.string.ui_edit_komga, form.editTitle)
        assertEquals(Res.string.ui_source_url, form.urlLabel)
        assertEquals(Res.string.ui_source_komga_help_text, form.helpText)
        assertEquals(Res.string.ui_remove_source, form.removeLabel)
    }

    @Test
    fun `source picker blurbs resolve through resources`() {
        assertEquals(Res.string.ui_source_abs_picker_blurb, sourcePickerBlurbRes(SourceType.ABS))
        assertEquals(Res.string.ui_source_local_files_picker_blurb, sourcePickerBlurbRes(SourceType.LOCAL_FILES))
        assertEquals(Res.string.ui_source_komga_picker_blurb, sourcePickerBlurbRes(SourceType.KOMGA))
    }

    @Test
    fun `local files has localized subtitle but no credentialed add-source copy`() {
        assertEquals(Res.string.ui_source_local_files_subtitle, sourceSubtitleRes(SourceType.LOCAL_FILES))
        assertNull(addSourceFormResources(SourceType.LOCAL_FILES, ServerType.AUDIOBOOKSHELF))
    }
}
