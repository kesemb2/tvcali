package io.github.kesemb2.calibrate.ui.chain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.diag.CheckId
import io.github.kesemb2.calibrate.diag.CheckResult
import io.github.kesemb2.calibrate.diag.Outcome
import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.diag.formatFps
import io.github.kesemb2.calibrate.ratetest.RateOutcome
import io.github.kesemb2.calibrate.ratetest.RateResult
import io.github.kesemb2.calibrate.ratetest.RateRules
import io.github.kesemb2.calibrate.ratetest.Request
import io.github.kesemb2.calibrate.ui.components.FocusCard
import io.github.kesemb2.calibrate.ui.components.Screen
import io.github.kesemb2.calibrate.ui.components.StatusChip
import io.github.kesemb2.calibrate.ui.components.StatusDot
import io.github.kesemb2.calibrate.ui.components.TvButton
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing

/** Results of one chain check run; null while it runs. */
data class ChainState(val results: List<CheckResult>, val sources: List<Pair<String, String>>)

@Composable
fun ChainScreen(
    state: ChainState?,
    onRerun: () -> Unit,
    rateResults: List<RateResult>?,
    rateAvailable: Boolean,
    onRunRateTest: () -> Unit,
) {
    val type = LocalGlassType.current
    val rerun = remember { FocusRequester() }
    Screen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(R.string.chain_title), style = type.title1.copy(color = GlassColors.TextPrimary))
                    val summary = state?.results?.let { r ->
                        stringResource(
                            R.string.chain_summary,
                            r.count { it.status == Status.Pass },
                            r.count { it.status == Status.Warn },
                            r.count { it.status == Status.Fail },
                        )
                    } ?: stringResource(R.string.chain_loading)
                    BasicText(summary, style = type.callout.copy(color = GlassColors.TextSecondary))
                }
                TvButton(stringResource(R.string.chain_rerun), onRerun, Modifier.focusRequester(rerun))
            }
            Spacer(Modifier.padding(top = Spacing.L))
            if (state != null) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Spacing.M),
                    // Room for the focused row to grow without clipping.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.M, horizontal = Spacing.S),
                ) {
                    items(state.results, key = { it.id }) { CheckRow(it) }
                    item(key = "rate") { RateSection(rateResults, rateAvailable, onRunRateTest) }
                    items(rateResults.orEmpty(), key = { "rate-${it.run.clipId}-${it.run.request}" }) { RateRow(it) }
                    item(key = "sources") { SourcesCard(state.sources) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { rerun.requestFocus() }
}

@Composable
private fun CheckRow(result: CheckResult) =
    ResultRow(result.status, stringResource(result.id.title()), result.value, stringResource(result.outcome.text()))

@Composable
private fun RateRow(r: RateResult) = ResultRow(
    status = RateRules.status(r),
    title = stringResource(
        if (r.run.request == Request.Explicit) R.string.rate_row_explicit else R.string.rate_row_default,
        formatFps(r.run.fps),
    ),
    value = RateRules.describe(r),
    text = stringResource(RateRules.outcome(r).text()),
)

@Composable
private fun RateSection(results: List<RateResult>?, available: Boolean, onRun: () -> Unit) {
    val type = LocalGlassType.current
    Column(Modifier.fillMaxWidth().padding(top = Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        BasicText(stringResource(R.string.rate_section_title), style = type.title2.copy(color = GlassColors.TextPrimary))
        BasicText(stringResource(R.string.rate_section_desc), style = type.callout.copy(color = GlassColors.TextSecondary))
        if (available) {
            TvButton(stringResource(R.string.rate_run_button), onRun)
        } else {
            BasicText(stringResource(R.string.rate_needs_patterns), style = type.callout.copy(color = GlassColors.TextSecondary))
        }
    }
}

@Composable
private fun ResultRow(status: Status, title: String, value: String, text: String) {
    val type = LocalGlassType.current
    FocusCard(Modifier.fillMaxWidth(), shape = Shapes.Row, focusedScale = 1.015f) { focused ->
        val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
        val inkSoft = if (focused) GlassColors.OnFocus.copy(alpha = 0.72f) else GlassColors.TextSecondary
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status)
            BasicText(title, Modifier.padding(start = Spacing.M).weight(1f), style = type.headline.copy(color = ink))
            // Technical values are Latin text; keep them left to right inside Hebrew layout.
            BasicText(
                value,
                Modifier.padding(start = Spacing.L),
                style = type.callout.copy(color = ink, textDirection = TextDirection.Ltr),
            )
            Spacer(Modifier.width(Spacing.L))
            StatusChip(status, stringResource(status.label()), inkSoft)
        }
        BasicText(text, Modifier.padding(start = 24.dp), style = type.callout.copy(color = inkSoft))
    }
}

@Composable
private fun SourcesCard(sources: List<Pair<String, String>>) {
    val type = LocalGlassType.current
    FocusCard(Modifier.fillMaxWidth(), shape = Shapes.Row, focusedScale = 1.01f) { focused ->
        val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
        val inkSoft = if (focused) GlassColors.OnFocus.copy(alpha = 0.72f) else GlassColors.TextSecondary
        BasicText(stringResource(R.string.chain_sources), style = type.headline.copy(color = ink))
        BasicText(stringResource(R.string.chain_sources_note), style = type.caption.copy(color = inkSoft))
        Spacer(Modifier.padding(top = Spacing.XS))
        // Raw values are English and technical: always left to right.
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sources.forEach { (key, value) ->
                    Row {
                        BasicText(key, Modifier.width(230.dp), style = type.caption.copy(color = ink))
                        BasicText(value, style = type.caption.copy(color = inkSoft))
                    }
                }
            }
        }
    }
}

private fun RateOutcome.text(): Int = when (this) {
    RateOutcome.Exact -> R.string.rate_outcome_exact
    RateOutcome.NearlyExact -> R.string.rate_outcome_nearly
    RateOutcome.Multiple -> R.string.rate_outcome_multiple
    RateOutcome.NoSwitch -> R.string.rate_outcome_no_switch
    RateOutcome.Wrong -> R.string.rate_outcome_wrong
    RateOutcome.DefaultNoSwitch -> R.string.rate_outcome_default_no_switch
    RateOutcome.DefaultSwitched -> R.string.rate_outcome_default_switched
}

private fun Status.label(): Int = when (this) {
    Status.Pass -> R.string.status_pass
    Status.Warn -> R.string.status_warn
    Status.Fail -> R.string.status_fail
    Status.Info -> R.string.status_info
}

private fun CheckId.title(): Int = when (this) {
    CheckId.Resolution -> R.string.check_resolution
    CheckId.RefreshRates -> R.string.check_refresh_rates
    CheckId.Hdr10 -> R.string.check_hdr10
    CheckId.DolbyVision -> R.string.check_dolby_vision
    CheckId.OtherHdr -> R.string.check_other_hdr
    CheckId.EdidLuminance -> R.string.check_edid_luminance
    CheckId.MatchFrameRate -> R.string.check_match_frame_rate
    CheckId.HdrConversion -> R.string.check_hdr_conversion
    CheckId.DisabledHdr -> R.string.check_disabled_hdr
    CheckId.HdmiOutput -> R.string.check_hdmi_output
    CheckId.Decoders -> R.string.check_decoders
}

private fun Outcome.text(): Int = when (this) {
    Outcome.Res4k -> R.string.outcome_res_4k
    Outcome.ResLow -> R.string.outcome_res_low
    Outcome.ResUnknown -> R.string.outcome_res_unknown
    Outcome.RatesAll -> R.string.outcome_rates_all
    Outcome.RatesNo24 -> R.string.outcome_rates_no24
    Outcome.RatesNoPal -> R.string.outcome_rates_no_pal
    Outcome.RatesUnknown -> R.string.outcome_rates_unknown
    Outcome.Hdr10Ok -> R.string.outcome_hdr10_ok
    Outcome.Hdr10Missing -> R.string.outcome_hdr10_missing
    Outcome.DvOk -> R.string.outcome_dv_ok
    Outcome.DvOkHidden -> R.string.outcome_dv_ok_hidden
    Outcome.DvMissing -> R.string.outcome_dv_missing
    Outcome.OtherHdrInfo -> R.string.outcome_other_hdr_info
    Outcome.LumOk -> R.string.outcome_lum_ok
    Outcome.LumEmpty -> R.string.outcome_lum_empty
    Outcome.LumDefault -> R.string.outcome_lum_default
    Outcome.LumUnknown -> R.string.outcome_lum_unknown
    Outcome.MatchAlways -> R.string.outcome_match_always
    Outcome.MatchSeamless -> R.string.outcome_match_seamless
    Outcome.MatchNever -> R.string.outcome_match_never
    Outcome.MatchUnknown -> R.string.outcome_match_unknown
    Outcome.ConvPassthrough -> R.string.outcome_conv_passthrough
    Outcome.ConvSystem -> R.string.outcome_conv_system
    Outcome.ConvForce -> R.string.outcome_conv_force
    Outcome.ConvUnknown -> R.string.outcome_conv_unknown
    Outcome.DisabledNone -> R.string.outcome_disabled_none
    Outcome.DisabledSome -> R.string.outcome_disabled_some
    Outcome.OutInfo -> R.string.outcome_out_info
    Outcome.OutUnknown -> R.string.outcome_out_unknown
    Outcome.DecOk -> R.string.outcome_dec_ok
    Outcome.DecNoDv -> R.string.outcome_dec_no_dv
    Outcome.DecNoHevc -> R.string.outcome_dec_no_hevc
    Outcome.DecUnknown -> R.string.outcome_dec_unknown
}
