package io.github.kesemb2.calibrate.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.patterns.DecoderCheck
import io.github.kesemb2.calibrate.patterns.PatternCatalog
import io.github.kesemb2.calibrate.patterns.PatternStore
import io.github.kesemb2.calibrate.ui.components.FocusCard
import io.github.kesemb2.calibrate.ui.components.Screen
import io.github.kesemb2.calibrate.ui.components.StatusDot
import io.github.kesemb2.calibrate.ui.components.TvButton
import io.github.kesemb2.calibrate.ui.components.focusScale
import io.github.kesemb2.calibrate.ui.components.tvClickable
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Spacing

/** Result of the decoder check over all patterns: null while running. */
data class DecoderSummary(val decoder: String?, val patterns: Int, val problems: List<String>, val error: String?, val bits: Int)

fun summarize(results: List<DecoderCheck.Result>): DecoderSummary {
    val error = results.firstOrNull { it.error != null }?.error
    return DecoderSummary(
        decoder = results.firstNotNullOfOrNull { it.decoder },
        patterns = results.size,
        problems = results.flatMap { it.mismatches },
        error = error,
        bits = results.minOfOrNull { it.bits } ?: 0,
    )
}

@Composable
fun PatternsScreen(
    state: PatternStore.State,
    decoder: DecoderSummary?,
    onRetry: () -> Unit,
    format: String,
    onFormat: (String) -> Unit,
    onOpen: (format: String, index: Int) -> Unit,
) {
    val type = LocalGlassType.current
    val first = remember { FocusRequester() }
    Screen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BasicText(stringResource(R.string.patterns_title), style = type.title1.copy(color = GlassColors.TextPrimary))
            BasicText(stringResource(R.string.patterns_subtitle), style = type.callout.copy(color = GlassColors.TextSecondary))
            Spacer(Modifier.height(Spacing.M))
            when (state) {
                is PatternStore.State.Idle, is PatternStore.State.Downloading -> {
                    val pct = (state as? PatternStore.State.Downloading)
                        ?.let { if (it.totalBytes > 0) (it.doneBytes * 100 / it.totalBytes).toInt() else 0 } ?: 0
                    BasicText(stringResource(R.string.patterns_downloading, pct), style = type.headline.copy(color = GlassColors.TextPrimary))
                }
                is PatternStore.State.Failed -> {
                    BasicText(stringResource(R.string.patterns_failed, state.message), style = type.headline.copy(color = GlassColors.TextPrimary))
                    Spacer(Modifier.height(Spacing.L))
                    TvButton(stringResource(R.string.patterns_retry), onRetry, Modifier.focusRequester(first))
                    LaunchedEffect(Unit) { first.requestFocus() }
                }
                is PatternStore.State.Ready -> {
                    DecoderLine(decoder)
                    Spacer(Modifier.height(Spacing.M))
                    val formats = state.manifest.formats
                    val current = format.takeIf { it in formats } ?: formats.first()
                    // One tab per format: the TV keeps separate picture settings for each.
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                        formats.forEach { f ->
                            FormatTab(
                                label = PatternCatalog.tabName(f)?.let { stringResource(it) } ?: f,
                                selected = f == current,
                                onClick = { onFormat(f) },
                            )
                        }
                    }
                    PatternCatalog.tabNote(current)?.let {
                        Spacer(Modifier.height(Spacing.S))
                        BasicText(stringResource(it), style = type.caption.copy(color = GlassColors.TextSecondary))
                    }
                    Spacer(Modifier.height(Spacing.S))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.L),
                        verticalArrangement = Arrangement.spacedBy(Spacing.L),
                        contentPadding = PaddingValues(Spacing.M),
                    ) {
                        itemsIndexed(state.manifest.ofFormat(current), key = { _, p -> p.id }) { i, p ->
                            val text = PatternCatalog.text(p)
                            FocusCard(
                                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).height(112.dp),
                                onClick = { onOpen(current, i) },
                            ) { focused ->
                                val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
                                val soft = if (focused) GlassColors.OnFocus.copy(alpha = 0.7f) else GlassColors.TextSecondary
                                BasicText(text?.let { stringResource(it.title) } ?: p.id, style = type.headline.copy(color = ink))
                                text?.let { BasicText(stringResource(it.short), style = type.callout.copy(color = soft)) }
                                PatternCatalog.formatLabel(p.format)?.let {
                                    BasicText(stringResource(it), style = type.caption.copy(color = soft))
                                }
                            }
                        }
                    }
                    LaunchedEffect(Unit) { first.requestFocus() }
                }
            }
        }
    }
}

/** A format tab: white when focused (like every focusable here), outlined when selected but not focused. */
@Composable
private fun FormatTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val type = LocalGlassType.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill = when {
        focused -> GlassColors.FocusFill
        selected -> GlassColors.GlassTintFocused
        else -> GlassColors.GlassTint
    }
    BasicText(
        label,
        style = type.headline.copy(color = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary),
        modifier = Modifier
            .focusScale(focused, false, Shapes.Pill, 1.06f)
            .clip(Shapes.Pill)
            .background(fill, Shapes.Pill)
            .then(if (selected && !focused) Modifier.border(1.5.dp, GlassColors.TextPrimary, Shapes.Pill) else Modifier)
            // Moving onto a tab selects it, so Left/Right flips formats without pressing OK.
            .onFocusChanged { if (it.isFocused && !selected) onClick() }
            .tvClickable(interaction, onClick = onClick)
            .padding(horizontal = Spacing.XL, vertical = Spacing.S),
    )
}

@Composable
private fun DecoderLine(d: DecoderSummary?) {
    val type = LocalGlassType.current
    val (status, text) = when {
        d == null -> Status.Info to stringResource(R.string.patterns_decoder_checking)
        d.error != null -> Status.Warn to stringResource(R.string.patterns_decoder_unavailable, d.error)
        d.problems.isNotEmpty() ->
            Status.Fail to stringResource(R.string.patterns_decoder_bad, d.decoder ?: "?", d.problems.take(3).joinToString("; "))
        d.bits == 10 -> Status.Pass to stringResource(R.string.patterns_decoder_ok, d.decoder ?: "?", d.patterns)
        else -> Status.Pass to stringResource(R.string.patterns_decoder_ok_8bit, d.decoder ?: "?", d.patterns)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        BasicText(text, Modifier.padding(start = Spacing.S), style = type.callout.copy(color = GlassColors.TextSecondary))
    }
}
