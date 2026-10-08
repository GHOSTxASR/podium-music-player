package app.podium.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import app.podium.core.designsystem.theme.PodiumMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * TACTILE (animation-system.md §2): the thing goes down under the finger and comes back up when it
 * lets go — down hard on the frame the finger lands ([PodiumMotion.pressIn]), up with a whisper of
 * spring ([PodiumMotion.pressOut]). The touch is only watched (the Initial pass, never consumed), so
 * whatever handles the tap — a `clickable`, a gesture detector — works exactly as before. The scale is
 * read only while drawing: a press never recomposes anything.
 *
 * @param pressedScale how far it goes down: 0.94 for a key, less for a large surface.
 */
fun Modifier.pressFeedback(pressedScale: Float = 0.94f, enabled: Boolean = true): Modifier =
    if (enabled) this then PressFeedbackElement(pressedScale) else this

/**
 * True while two or more fingers are on the device: a pinch may be starting (D-54). Holds don't
 * fire then — a slow pinch that begins on a row or on the Wheel must not open a menu, go Home or
 * switch the display off before it has moved far enough to be recognised (D-65). Provided by the
 * shell, which watches every touch first.
 */
val LocalSeveralFingers = staticCompositionLocalOf<() -> Boolean> { { false } }

/** One key's travel, for callers that drive the press themselves (the Wheel tracks its own touches). */
class KeyTravel(var pressedScale: Float) {
    private val scale = Animatable(1f)

    /** The scale to draw with; read it in a draw-phase block (`graphicsLayer { }`). */
    val value: Float get() = scale.value

    /** Starts the press at once (undispatched), interrupting any release still settling. */
    fun press(scope: CoroutineScope) = go(scope, pressedScale, PodiumMotion.pressIn())

    fun release(scope: CoroutineScope) = go(scope, 1f, PodiumMotion.pressOut())

    private fun go(scope: CoroutineScope, target: Float, spec: AnimationSpec<Float>) {
        if (scale.targetValue == target && scale.isRunning) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scale.animateTo(target, spec) }
    }
}

private data class PressFeedbackElement(val pressedScale: Float) : ModifierNodeElement<PressFeedbackNode>() {
    override fun create() = PressFeedbackNode(pressedScale)

    override fun update(node: PressFeedbackNode) {
        node.travel.pressedScale = pressedScale
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressFeedback"
        properties["pressedScale"] = pressedScale
    }
}

private class PressFeedbackNode(pressedScale: Float) : Modifier.Node(), PointerInputModifierNode, LayoutModifierNode {
    val travel = KeyTravel(pressedScale)
    private var down = false
    private val layer: GraphicsLayerScope.() -> Unit = {
        val s = travel.value
        scaleX = s
        scaleY = s
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val anyDown = pointerEvent.changes.any { it.pressed }
        if (anyDown && !down) {
            down = true
            travel.press(coroutineScope)
        } else if (!anyDown && down) {
            down = false
            travel.release(coroutineScope)
        }
    }

    override fun onCancelPointerInput() {
        if (down) {
            down = false
            travel.release(coroutineScope)
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}
