package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import com.riffle.feature.reader.ui.ReturnToPositionCard as SharedReturnToPositionCard

const val TAG_RETURN_CARD = "return_to_position_card"
const val TAG_RETURN_BACK = "return_to_position_back"
const val TAG_RETURN_DISMISS = "return_to_position_dismiss"

@Composable
fun ReturnToPositionCard(
    onReturn: () -> Unit,
    onDismiss: () -> Unit,
) = SharedReturnToPositionCard(onReturn = onReturn, onDismiss = onDismiss)
