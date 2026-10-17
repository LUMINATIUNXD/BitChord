package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chrisbanes.haze.HazeState

/** Whether the earbuds drawer is up. Read by the player's overlays and set by the button beside the menu. */
internal object OutputExtrasUi {
    var open by mutableStateOf(false)
}

/** The earbuds' own drawer, in the same shell as every other player sheet. */
@Composable
internal fun OutputExtrasSheet(hazeState: HazeState, onDismiss: () -> Unit) {
    PlayerDrawer(
        hazeState = hazeState,
        title = "Aud\u00EDfonos",
        onDismiss = onDismiss,
    ) {
        PlayerPlatform.host.OutputExtras()
    }
}