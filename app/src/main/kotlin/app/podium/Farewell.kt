package app.podium

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import app.podium.space.SpaceType
import kotlinx.coroutines.delay

/**
 * Turn off Podium (D-56): the music has already stopped; the window darkens, Podium says goodbye,
 * and then [onFinished] closes it for good. Nothing beneath can be touched meanwhile.
 */
@Composable
fun Farewell(onFinished: () -> Unit) = SpaceType {
    val finished by rememberUpdatedState(onFinished)
    val dark = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        dark.animateTo(1f, tween(420))
        words.animateTo(1f, tween(520))
        delay(1_000)
        words.animateTo(0f, tween(360))
        finished()
    }
    BackHandler {} // already going
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .graphicsLayer { alpha = dark.value }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer { alpha = words.value; translationY = (1f - words.value) * 8.dp.toPx() }
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PodiumText("Goodbye", PodiumTheme.type.title, Color.White, fontSize = 34.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            PodiumText("Your music has stopped. See you soon.", PodiumTheme.type.caption, CarbonColors.labelSecondary, textAlign = TextAlign.Center)
        }
    }
}
