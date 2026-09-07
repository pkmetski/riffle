package com.riffle.feature.source.ui

import androidx.compose.runtime.Composable
import com.riffle.core.domain.AddSourceCopy
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.source_audiobookshelf_name
import com.riffle.feature.source.ui.generated.resources.source_chitanka_name
import com.riffle.feature.source.ui.generated.resources.source_oreilly_name
import com.riffle.feature.source.ui.generated.resources.ui_source_oreilly_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_oreilly_subtitle
import com.riffle.feature.source.ui.generated.resources.source_komga_name
import com.riffle.feature.source.ui.generated.resources.source_project_gutenberg_name
import com.riffle.feature.source.ui.generated.resources.source_radio_es_name
import com.riffle.feature.source.ui.generated.resources.ui_add_audiobookshelf
import com.riffle.feature.source.ui.generated.resources.ui_add_komga
import com.riffle.feature.source.ui.generated.resources.ui_add_storyteller
import com.riffle.feature.source.ui.generated.resources.ui_edit_audiobookshelf
import com.riffle.feature.source.ui.generated.resources.ui_edit_komga
import com.riffle.feature.source.ui.generated.resources.ui_edit_storyteller
import com.riffle.feature.source.ui.generated.resources.ui_local_files
import com.riffle.feature.source.ui.generated.resources.ui_remove_source
import com.riffle.feature.source.ui.generated.resources.ui_remove_storyteller
import com.riffle.feature.source.ui.generated.resources.ui_source_abs_help_text
import com.riffle.feature.source.ui.generated.resources.ui_source_abs_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_chitanka_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_chitanka_subtitle
import com.riffle.feature.source.ui.generated.resources.ui_source_gutenberg_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_gutenberg_subtitle
import com.riffle.feature.source.ui.generated.resources.ui_source_komga_help_text
import com.riffle.feature.source.ui.generated.resources.ui_source_komga_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_local_files_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_local_files_subtitle
import com.riffle.feature.source.ui.generated.resources.ui_source_radio_es_picker_blurb
import com.riffle.feature.source.ui.generated.resources.ui_source_storyteller_help_text
import com.riffle.feature.source.ui.generated.resources.ui_source_url
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The five localizable copy slots of the credentialed Add-Source form. Kept as
 * [StringResource]s (not resolved strings) so the mapping stays testable without a Compose
 * runtime, exactly as the Android `@StringRes Int` version was.
 */
data class AddSourceFormResources(
    val addTitle: StringResource,
    val editTitle: StringResource,
    val urlLabel: StringResource,
    val helpText: StringResource,
    val removeLabel: StringResource,
)

internal fun sourceDisplayNameRes(type: SourceType): StringResource = when (type) {
    SourceType.ABS -> Res.string.source_audiobookshelf_name
    SourceType.LOCAL_FILES -> Res.string.ui_local_files
    SourceType.CHITANKA -> Res.string.source_chitanka_name
    SourceType.GUTENBERG -> Res.string.source_project_gutenberg_name
    SourceType.KOMGA -> Res.string.source_komga_name
    SourceType.RADIO_ES -> Res.string.source_radio_es_name
    SourceType.OREILLY -> Res.string.source_oreilly_name
}

internal fun sourceSubtitleRes(type: SourceType): StringResource? = when (type) {
    SourceType.LOCAL_FILES -> Res.string.ui_source_local_files_subtitle
    SourceType.CHITANKA -> Res.string.ui_source_chitanka_subtitle
    SourceType.GUTENBERG -> Res.string.ui_source_gutenberg_subtitle
    SourceType.OREILLY -> Res.string.ui_source_oreilly_subtitle
    SourceType.ABS,
    SourceType.KOMGA,
    SourceType.RADIO_ES,
    -> null
}

internal fun sourcePickerBlurbRes(type: SourceType): StringResource = when (type) {
    SourceType.ABS -> Res.string.ui_source_abs_picker_blurb
    SourceType.LOCAL_FILES -> Res.string.ui_source_local_files_picker_blurb
    SourceType.CHITANKA -> Res.string.ui_source_chitanka_picker_blurb
    SourceType.GUTENBERG -> Res.string.ui_source_gutenberg_picker_blurb
    SourceType.KOMGA -> Res.string.ui_source_komga_picker_blurb
    SourceType.RADIO_ES -> Res.string.ui_source_radio_es_picker_blurb
    SourceType.OREILLY -> Res.string.ui_source_oreilly_picker_blurb
}

internal fun addSourceFormResources(type: SourceType, serverType: ServerType): AddSourceFormResources? =
    when (type) {
        SourceType.ABS -> when (serverType) {
            ServerType.AUDIOBOOKSHELF -> AddSourceFormResources(
                addTitle = Res.string.ui_add_audiobookshelf,
                editTitle = Res.string.ui_edit_audiobookshelf,
                urlLabel = Res.string.ui_source_url,
                helpText = Res.string.ui_source_abs_help_text,
                removeLabel = Res.string.ui_remove_source,
            )
            ServerType.STORYTELLER_SERVICE -> AddSourceFormResources(
                addTitle = Res.string.ui_add_storyteller,
                editTitle = Res.string.ui_edit_storyteller,
                urlLabel = Res.string.ui_source_url,
                helpText = Res.string.ui_source_storyteller_help_text,
                removeLabel = Res.string.ui_remove_storyteller,
            )
        }
        SourceType.KOMGA -> AddSourceFormResources(
            addTitle = Res.string.ui_add_komga,
            editTitle = Res.string.ui_edit_komga,
            urlLabel = Res.string.ui_source_url,
            helpText = Res.string.ui_source_komga_help_text,
            removeLabel = Res.string.ui_remove_source,
        )
        // O'Reilly authenticates via a WebView login screen, not the shared credential form.
        SourceType.OREILLY,
        SourceType.LOCAL_FILES,
        SourceType.CHITANKA,
        SourceType.GUTENBERG,
        SourceType.RADIO_ES,
        -> null
    }

@Composable
fun localizedSourceDisplayName(descriptor: WebSourceDescriptor): String =
    stringResource(sourceDisplayNameRes(descriptor.type))

@Composable
fun localizedSourceSubtitle(descriptor: WebSourceDescriptor): String? =
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
