package io.github.kesemb2.calibrate.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.ui.theme.Springs

/**
 * Glass Home's focus language without its live glass: focused elements grow and lift with a spring. (Calibrate's
 * panels sit over video on a separate hardware plane, which nothing in the UI layer can blur or refract.)
 */
fun Modifier.focusScale(focused: Boolean, pressed: Boolean, shape: Shape, focusedScale: Float = 1.06f): Modifier =
    composed {
        val scale by animateFloatAsState(
            when {
                pressed -> 1f + (focusedScale - 1f) / 2f
                focused -> focusedScale
                else -> 1f
            },
            Springs.focus(), label = "focusScale",
        )
        val lift by animateFloatAsState(if (focused) 1f else 0f, Springs.focus(), label = "focusLift")
        graphicsLayer {
            scaleX = scale
            scaleY = scale
            shadowElevation = 12.dp.toPx() * lift
            this.shape = shape
            clip = false
        }
    }

/** OK on the remote clicks; the press shows while the key is down, and a key-up without its key-down is ignored. */
fun Modifier.tvClickable(interactionSource: MutableInteractionSource, enabled: Boolean = true, onClick: () -> Unit):
    Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val state = remember { PressState() }
    onKeyEvent { event ->
        if (!event.isSelect()) return@onKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    state.active = true
                    state.press = PressInteraction.Press(Offset.Zero).also { interactionSource.tryEmit(it) }
                }
                true
            }
            KeyEventType.KeyUp -> {
                val fire = state.active
                state.active = false
                state.press?.let { interactionSource.tryEmit(PressInteraction.Release(it)) }
                state.press = null
                if (fire && enabled) click()
                true
            }
            else -> false
        }
    }.focusable(interactionSource = interactionSource)
}

private class PressState {
    var active = false
    var press: PressInteraction.Press? = null
}

private fun KeyEvent.isSelect(): Boolean = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
