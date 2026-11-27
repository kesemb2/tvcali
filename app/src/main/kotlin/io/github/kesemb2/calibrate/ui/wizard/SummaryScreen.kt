package io.github.kesemb2.calibrate.ui.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.ui.components.FocusCard
import io.github.kesemb2.calibrate.ui.components.Screen
import io.github.kesemb2.calibrate.ui.components.StatusDot
import io.github.kesemb2.calibrate.ui.components.TvButton
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing
import io.github.kesemb2.calibrate.wizard.StepStatus
import io.github.kesemb2.calibrate.wizard.WizardContent
import io.github.kesemb2.calibrate.wizard.WizardExport
import io.github.kesemb2.calibrate.wizard.WizardStore

/** Every flow's chosen values, and a QR code per flow to keep them on the phone. */
@Composable
fun SummaryScreen(content: WizardContent, store: WizardStore) {
    val type = LocalGlassType.current
    var flowId by remember { mutableStateOf(content.flows.first().id) }
    val flow = content.flow(flowId) ?: content.flows.first()
    val labels = mapOf(
        StepStatus.Done to stringResource(R.string.status_done),
        StepStatus.Skipped to stringResource(R.string.status_skipped),
        StepStatus.NotFound to stringResource(R.string.status_not_found),
    )
    val open = stringResource(R.string.status_open)
    val text = WizardExport.text(content, flow, store) { labels[it] ?: open }
    val first = remember { FocusRequester() }

    Screen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            BasicText(stringResource(R.string.summary_title), style = type.title1.copy(color = GlassColors.TextPrimary))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                content.flows.forEachIndexed { i, f ->
                    TvButton(f.title, { flowId = f.id }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.XXL)) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    items(content.stepsOf(flow), key = { it.step.id }) { s ->
                        val r = store.record(flow.id, s.step.id)
                        FocusCard(Modifier.fillMaxWidth(), shape = Shapes.Row, focusedScale = 1.01f) { focused ->
                            val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
                            val soft = if (focused) GlassColors.OnFocus.copy(alpha = 0.7f) else GlassColors.TextSecondary
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    when (r?.status) {
                                        StepStatus.Done -> Status.Pass
                                        StepStatus.Skipped -> Status.Info
                                        StepStatus.NotFound -> Status.Warn
                                        null -> Status.Info
                                    },
                                )
                                BasicText(s.step.title, Modifier.padding(start = Spacing.S).weight(1f), style = type.headline.copy(color = ink))
                                BasicText(labels[r?.status] ?: open, style = type.caption.copy(color = soft))
                            }
                            BasicText(
                                r?.value?.takeIf { it.isNotBlank() } ?: s.step.recommended,
                                Modifier.padding(start = 20.dp),
                                style = type.callout.copy(color = soft),
                            )
                        }
                    }
                }
                Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    QrCode(text, Modifier.size(280.dp))
                    BasicText(stringResource(R.string.summary_scan, flow.title), style = type.caption.copy(color = GlassColors.TextSecondary))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Black modules on white with a quiet zone, drawn crisp (one rectangle per module). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                    EncodeHintType.MARGIN to 2,
                ),
            )
        }.getOrNull()
    }
    Canvas(modifier.background(Color.White)) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
