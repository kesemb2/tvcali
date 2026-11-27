package io.github.kesemb2.calibrate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing

/**
 * A full-screen editor for one line of text, opened from a row (typing is rare on a TV, so fields are not editable in
 * place: focusing them while moving around would pop the keyboard up). Enter / Done saves; Back cancels.
 */
@Composable
fun TextEditOverlay(title: String, initial: String, saveLabel: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val type = LocalGlassType.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    androidx.activity.compose.BackHandler { onCancel() }
    Box(Modifier.fillMaxSize().background(Color(0xE605060A)), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 60.dp).width(640.dp).glassSurface(Shapes.Panel).padding(Spacing.XXL),
            verticalArrangement = Arrangement.spacedBy(Spacing.L),
        ) {
            BasicText(title, style = type.title2.copy(color = GlassColors.TextPrimary))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = type.body.copy(color = GlassColors.TextPrimary),
                cursorBrush = SolidColor(GlassColors.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(Shapes.Row, GlassColors.GlassTintFocused)
                    .padding(Spacing.L)
                    .focusRequester(focus),
            )
            Row { TvButton(saveLabel, { onSave(value.text) }) }
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}
