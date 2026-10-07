package com.riffle.feature.reader.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_active_suffix
import com.riffle.feature.reader.ui.generated.resources.ui_add_a_note
import com.riffle.feature.reader.ui.generated.resources.ui_bookmark_this_page
import com.riffle.feature.reader.ui.generated.resources.ui_cancel
import com.riffle.feature.reader.ui.generated.resources.ui_delete_annotation
import com.riffle.feature.reader.ui.generated.resources.ui_edit
import com.riffle.feature.reader.ui.generated.resources.ui_emphasis
import com.riffle.feature.reader.ui.generated.resources.ui_highlight_suffix
import com.riffle.feature.reader.ui.generated.resources.ui_no_highlight_color
import com.riffle.feature.reader.ui.generated.resources.ui_note
import com.riffle.feature.reader.ui.generated.resources.ui_remove
import com.riffle.feature.reader.ui.generated.resources.ui_remove_bookmark
import com.riffle.feature.reader.ui.generated.resources.ui_save
import com.riffle.feature.reader.ui.generated.resources.ui_selected_suffix
import org.jetbrains.compose.resources.stringResource

/** Builds [AnnotationSheetLabels] from composeResources, picking up the active locale. */
@Composable
fun annotationSheetLabels(): AnnotationSheetLabels = AnnotationSheetLabels(
    noHighlightColor = stringResource(Res.string.ui_no_highlight_color),
    selectedSuffix = stringResource(Res.string.ui_selected_suffix),
    highlightSuffix = stringResource(Res.string.ui_highlight_suffix),
    emphasis = stringResource(Res.string.ui_emphasis),
    activeSuffix = stringResource(Res.string.ui_active_suffix),
    note = stringResource(Res.string.ui_note),
    addNote = stringResource(Res.string.ui_add_a_note),
    edit = stringResource(Res.string.ui_edit),
    save = stringResource(Res.string.ui_save),
    remove = stringResource(Res.string.ui_remove),
    cancel = stringResource(Res.string.ui_cancel),
    delete = stringResource(Res.string.ui_delete_annotation),
    bookmark = stringResource(Res.string.ui_bookmark_this_page),
    removeBookmark = stringResource(Res.string.ui_remove_bookmark),
)
