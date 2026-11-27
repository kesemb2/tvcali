package io.github.kesemb2.calibrate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing
import io.github.kesemb2.calibrate.ui.theme.Springs
import io.github.kesemb2.calibrate.ui.theme.StatusColors

/** A static glass surface: translucent tint with a thin rim, as in Glass Home's cached glass. */
fun Modifier.glassSurface(shape: Shape, tint: Color = GlassColors.GlassTint): Modifier =
    clip(shape).background(tint, shape).border(1.dp, GlassColors.GlassRim.copy(alpha = 0.22f), shape)

/** Pill button: glass at rest, white with dark text when focused. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (focused) GlassColors.FocusFill else GlassColors.GlassTint, Springs.color(), label = "fill")
    val ink by animateColorAsState(if (focused) GlassColors.OnFocus else GlassColors.TextPrimary, Springs.color(), label = "ink")
    BasicText(
        text = text,
        style = LocalGlassType.current.headline.copy(color = ink),
        modifier = modifier
            .focusScale(focused, pressed, Shapes.Pill, 1.08f)
            .clip(Shapes.Pill)
            .background(fill, Shapes.Pill)
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = Spacing.XXL, vertical = Spacing.M),
    )
}

/**
 * A focusable card or row. Focus fills it white; [content] reads the current ink colour from its argument so text
 * stays legible on both fills.
 */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Card,
    enabled: Boolean = true,
    focusedScale: Float = 1.04f,
    onClick: () -> Unit = {},
    content: @Composable ColumnScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            focused && enabled -> GlassColors.FocusFill
            focused -> GlassColors.GlassTintFocused
            else -> GlassColors.GlassTint
        },
        Springs.color(), label = "cardFill",
    )
    Column(
        modifier = modifier
            .focusScale(focused, pressed, shape, focusedScale)
            .clip(shape)
            .background(fill, shape)
            .tvClickable(interaction, enabled, onClick)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) { content(focused && enabled) }
}

fun Status.color(): Color = when (this) {
    Status.Pass -> StatusColors.Pass
    Status.Warn -> StatusColors.Warn
    Status.Fail -> StatusColors.Fail
    Status.Info -> StatusColors.Info
}

@Composable
fun StatusDot(status: Status, modifier: Modifier = Modifier, size: Int = 12) {
    Box(modifier.size(size.dp).clip(CircleShape).background(status.color()))
}

/** Small status label: dot + word. */
@Composable
fun StatusChip(status: Status, label: String, ink: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        BasicText(label, Modifier.padding(start = Spacing.S), style = LocalGlassType.current.callout.copy(color = ink))
    }
}

@Composable
fun Screen(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .background(GlassColors.Background)
            .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.ScreenVertical),
        content = content,
    )
}
