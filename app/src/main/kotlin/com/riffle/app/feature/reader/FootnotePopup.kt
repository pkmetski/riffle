package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import com.riffle.feature.reader.ui.FootnotePopup as SharedFootnotePopup

const val TAG_FOOTNOTE_POPUP = "footnote_popup"

@Composable
fun FootnotePopup(
    state: FootnotePopupState,
    onDismiss: () -> Unit,
    onLinkTap: ((String) -> Unit)? = null,
) = SharedFootnotePopup(state = state, onDismiss = onDismiss, onLinkTap = onLinkTap)
