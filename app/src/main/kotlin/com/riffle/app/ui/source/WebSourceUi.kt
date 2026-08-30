package com.riffle.app.ui.source

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.core.domain.AddSourceCopy
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType

data class AddSourceFormResources(
    @StringRes val addTitle: Int,
    @StringRes val editTitle: Int,
    @StringRes val urlLabel: Int,
    @StringRes val helpText: Int,
    @StringRes val removeLabel: Int,
)

@StringRes
internal fun sourceDisplayNameRes(type: SourceType): Int = when (type) {
    SourceType.ABS -> R.string.source_audiobookshelf_name
    SourceType.LOCAL_FILES -> R.string.ui_local_files
    SourceType.CHITANKA -> R.string.source_chitanka_name
    SourceType.GUTENBERG -> R.string.source_project_gutenberg_name
    SourceType.KOMGA -> R.string.source_komga_name
    SourceType.RADIO_ES -> R.string.source_radio_es_name
}

@StringRes
internal fun sourceSubtitleRes(type: SourceType): Int? = when (type) {
    SourceType.LOCAL_FILES -> R.string.ui_source_local_files_subtitle
    SourceType.CHITANKA -> R.string.ui_source_chitanka_subtitle
    SourceType.GUTENBERG -> R.string.ui_source_gutenberg_subtitle
    SourceType.ABS,
    SourceType.KOMGA,
    SourceType.RADIO_ES,
    -> null
}

@StringRes
internal fun sourcePickerBlurbRes(type: SourceType): Int = when (type) {
    SourceType.ABS -> R.string.ui_source_abs_picker_blurb
    SourceType.LOCAL_FILES -> R.string.ui_source_local_files_picker_blurb
    SourceType.CHITANKA -> R.string.ui_source_chitanka_picker_blurb
    SourceType.GUTENBERG -> R.string.ui_source_gutenberg_picker_blurb
    SourceType.KOMGA -> R.string.ui_source_komga_picker_blurb
    SourceType.RADIO_ES -> R.string.ui_source_radio_es_picker_blurb
}

internal fun addSourceFormResources(type: SourceType, serverType: ServerType): AddSourceFormResources? =
    when (type) {
        SourceType.ABS -> when (serverType) {
            ServerType.AUDIOBOOKSHELF -> AddSourceFormResources(
                addTitle = R.string.ui_add_audiobookshelf,
                editTitle = R.string.ui_edit_audiobookshelf,
                urlLabel = R.string.ui_source_url,
                helpText = R.string.ui_source_abs_help_text,
                removeLabel = R.string.ui_remove_source,
            )
            ServerType.STORYTELLER_SERVICE -> AddSourceFormResources(
                addTitle = R.string.ui_add_storyteller,
                editTitle = R.string.ui_edit_storyteller,
                urlLabel = R.string.ui_source_url,
                helpText = R.string.ui_source_storyteller_help_text,
                removeLabel = R.string.ui_remove_storyteller,
            )
        }
        SourceType.KOMGA -> AddSourceFormResources(
            addTitle = R.string.ui_add_komga,
            editTitle = R.string.ui_edit_komga,
            urlLabel = R.string.ui_source_url,
            helpText = R.string.ui_source_komga_help_text,
            removeLabel = R.string.ui_remove_source,
        )
        SourceType.LOCAL_FILES,
        SourceType.CHITANKA,
        SourceType.GUTENBERG,
        SourceType.RADIO_ES,
        -> null
    }

@Composable
internal fun localizedSourceDisplayName(descriptor: WebSourceDescriptor): String =
    stringResource(sourceDisplayNameRes(descriptor.type))

@Composable
internal fun localizedSourceSubtitle(descriptor: WebSourceDescriptor): String? =
    sourceSubtitleRes(descriptor.type)?.let { stringResource(it) }

@Composable
internal fun localizedSourcePickerBlurb(type: SourceType): String =
    stringResource(sourcePickerBlurbRes(type))

@Composable
internal fun localizedAddSourceCopy(
    descriptor: WebSourceDescriptor,
    serverType: ServerType,
): AddSourceCopy? {
    val fallback = descriptor.addSourceCopyFor(serverType) ?: return null
    val resources = addSourceFormResources(descriptor.type, serverType) ?: return fallback
    return fallback.copy(
        addTitle = stringResource(resources.addTitle),
        editTitle = stringResource(resources.editTitle),
        urlLabel = stringResource(resources.urlLabel),
        helpText = stringResource(resources.helpText),
        removeLabel = stringResource(resources.removeLabel),
    )
}
