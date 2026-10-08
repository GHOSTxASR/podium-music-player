package app.podium.stickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.space.PanelButton
import app.podium.space.PodiumSpaceState
import app.podium.space.SpaceType

/**
 * Under the Podium while stickers are arranged: what a touch does, take the chosen one off, Done.
 * Comes in as the Podium comes square on.
 */
@Composable
fun BoxScope.StickerEditorBar(space: PodiumSpaceState, editor: StickerEditorState, store: StickerStore) = SpaceType {
    val ink = CarbonColors
    val chosen = editor.selected
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(Spacing.l)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .graphicsLayer {
                val shown = space.edit.value
                alpha = shown
                translationY = (1f - shown) * 32.dp.toPx()
            }
            .background(ink.canvasRaised, Shape)
            .border(1.dp, ink.separator, Shape)
            .padding(Spacing.m),
    ) {
        PodiumText(
            if (chosen == null) "Touch a sticker to choose it." else "Drag it to move it. Two fingers resize and turn it.",
            PodiumTheme.type.caption,
            ink.labelSecondary,
            maxLines = 2,
        )
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PanelButton(
                "Take it off",
                {
                    chosen?.let(store::removePlacement)
                    editor.selected = null
                },
                Modifier.weight(1f),
                emphasized = false,
                color = if (chosen == null) ink.labelTertiary else ink.critical,
            )
            PanelButton(
                "Done",
                {
                    editor.selected = null
                    space.stopEditing()
                },
                Modifier.weight(1f),
            )
        }
    }
}

private val Shape = RoundedCornerShape(18.dp)
