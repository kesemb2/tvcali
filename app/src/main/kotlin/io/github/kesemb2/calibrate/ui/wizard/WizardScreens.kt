package io.github.kesemb2.calibrate.ui.wizard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.patterns.PatternFile
import io.github.kesemb2.calibrate.ui.components.FocusCard
import io.github.kesemb2.calibrate.ui.components.Screen
import io.github.kesemb2.calibrate.ui.components.StatusDot
import io.github.kesemb2.calibrate.ui.components.TextEditOverlay
import io.github.kesemb2.calibrate.ui.components.TvButton
import io.github.kesemb2.calibrate.ui.components.glassSurface
import io.github.kesemb2.calibrate.ui.patterns.PatternVideo
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing
import io.github.kesemb2.calibrate.wizard.ResolvedStep
import io.github.kesemb2.calibrate.wizard.StepRecord
import io.github.kesemb2.calibrate.wizard.StepStatus
import io.github.kesemb2.calibrate.wizard.WizardContent
import io.github.kesemb2.calibrate.wizard.WizardFlow
import io.github.kesemb2.calibrate.wizard.WizardStore
import java.io.File

/** Looks a pattern file up by id; null while the patterns are not downloaded. */
typealias PatternLookup = (String) -> Pair<PatternFile, File>?

// ---- Flow list ---------------------------------------------------------------------------------------------

@Composable
fun WizardHomeScreen(
    content: WizardContent,
    store: WizardStore,
    patternsReady: Boolean,
    onOpenFlow: (String) -> Unit,
    onSummary: () -> Unit,
) {
    val type = LocalGlassType.current
    val first = remember { FocusRequester() }
    Screen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            BasicText(stringResource(R.string.wizard_title), style = type.title1.copy(color = GlassColors.TextPrimary))
            BasicText(stringResource(R.string.wizard_subtitle), style = type.callout.copy(color = GlassColors.TextSecondary))
            if (!patternsReady) {
                BasicText(stringResource(R.string.wizard_needs_patterns), style = type.headline.copy(color = GlassColors.TextPrimary))
            }
            Spacer(Modifier.height(Spacing.S))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
                content.flows.forEachIndexed { i, flow ->
                    val done = flow.stepIds.count { store.record(flow.id, it) != null }
                    FocusCard(
                        modifier = Modifier.weight(1f).height(150.dp).let { if (i == 0) it.focusRequester(first) else it },
                        enabled = patternsReady,
                        onClick = { onOpenFlow(flow.id) },
                    ) { focused ->
                        val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
                        val soft = if (focused) GlassColors.OnFocus.copy(alpha = 0.7f) else GlassColors.TextSecondary
                        BasicText(flow.title, style = type.title2.copy(color = ink))
                        BasicText(flow.subtitle, style = type.caption.copy(color = soft))
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(
                                when {
                                    done == 0 -> Status.Info
                                    done < flow.stepIds.size -> Status.Warn
                                    else -> Status.Pass
                                },
                            )
                            BasicText(
                                stringResource(R.string.wizard_progress, done, flow.stepIds.size),
                                Modifier.padding(start = Spacing.S),
                                style = type.callout.copy(color = soft),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.S))
            Row { TvButton(stringResource(R.string.wizard_summary), onSummary) }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

// ---- One flow ----------------------------------------------------------------------------------------------

/**
 * A flow: an intro that plays the format (so the TV switches to its mode), then one screen per step with the step's
 * pattern full screen behind a card. "Show the pattern" hides the card; OK brings it back.
 */
@Composable
fun WizardFlowScreen(
    content: WizardContent,
    flow: WizardFlow,
    store: WizardStore,
    patternOf: PatternLookup,
    onExit: () -> Unit,
    onSummary: () -> Unit,
) {
    val steps = remember(flow.id) { content.stepsOf(flow) }
    // -1 = intro, steps.size = finished.
    var index by remember { mutableIntStateOf(-1) }
    var cardVisible by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }  // "path" | "value"
    var revision by remember { mutableIntStateOf(0) }  // bumps after saving, to re-read the store
    val rootFocus = remember { FocusRequester() }

    val patternId = steps.getOrNull(index)?.patternId ?: flow.startPattern.let {
        if (flow.isSdr) it else WizardContent.forFormat(it, flow.format)
    }
    val pattern = patternOf(patternId)

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                // With the card hidden the screen is all pattern; OK brings the instructions back.
                if (!cardVisible && e.type == KeyEventType.KeyUp &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                ) {
                    cardVisible = true
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        PatternVideo(pattern?.first, pattern?.second)
        AnimatedVisibility(
            visible = cardVisible,
            enter = fadeIn(spring(stiffness = 400f)),
            exit = fadeOut(spring(stiffness = 400f)),
            modifier = Modifier.align(Alignment.CenterEnd).padding(Spacing.ScreenVertical).fillMaxHeight(),
        ) {
            StepCard(
                flow = flow,
                steps = steps,
                index = index,
                store = store,
                revision = revision,
                onStart = { index = it },
                onResult = { status ->
                    val step = steps[index].step
                    store.save(flow.id, step.id, StepRecord(status, store.value(flow.id, step.id), System.currentTimeMillis()))
                    revision++
                    index++
                },
                onHide = {
                    cardVisible = false
                    runCatching { rootFocus.requestFocus() }
                },
                onEdit = { editing = it },
                onExit = onExit,
                onSummary = onSummary,
            )
        }
        val step = steps.getOrNull(index)?.step
        if (editing != null && step != null) {
            val path = editing == "path"
            TextEditOverlay(
                title = stringResource(if (path) R.string.wizard_edit_path else R.string.wizard_edit_value),
                initial = if (path) store.menuPath(step.id) ?: step.menu else store.value(flow.id, step.id),
                saveLabel = stringResource(R.string.wizard_save),
                onSave = { text ->
                    if (path) {
                        store.saveMenuPath(step.id, text)
                    } else {
                        val old = store.record(flow.id, step.id)
                        store.save(flow.id, step.id, StepRecord(old?.status ?: StepStatus.Done, text.trim(), System.currentTimeMillis()))
                    }
                    revision++
                    editing = null
                },
                onCancel = { editing = null },
            )
        }
    }
}

@Composable
private fun StepCard(
    flow: WizardFlow,
    steps: List<ResolvedStep>,
    index: Int,
    store: WizardStore,
    revision: Int,
    onStart: (Int) -> Unit,
    onResult: (StepStatus) -> Unit,
    onHide: () -> Unit,
    onEdit: (String) -> Unit,
    onExit: () -> Unit,
    onSummary: () -> Unit,
) {
    val type = LocalGlassType.current
    val primary = remember(index) { FocusRequester() }
    Column(
        Modifier
            .width(460.dp)
            .fillMaxHeight()
            // Dark glass: the card floats over white patterns as well as black ones.
            .glassSurface(Shapes.Panel, tint = Color(0xE00B0D14))
            .verticalScroll(rememberScrollState())
            .padding(Spacing.XXL),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        when {
            index < 0 -> {
                val firstOpen = steps.indexOfFirst { store.record(flow.id, it.step.id) == null }
                BasicText(flow.title, style = type.title1.copy(color = GlassColors.TextPrimary))
                BasicText(flow.intro, style = type.body.copy(color = GlassColors.TextPrimary))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    if (firstOpen > 0) {
                        TvButton(stringResource(R.string.wizard_continue, firstOpen + 1), { onStart(firstOpen) }, Modifier.focusRequester(primary))
                        TvButton(stringResource(R.string.wizard_restart), { onStart(0) })
                    } else {
                        TvButton(stringResource(R.string.wizard_start), { onStart(0) }, Modifier.focusRequester(primary))
                    }
                    TvButton(stringResource(R.string.wizard_hide), onHide)
                }
            }
            index >= steps.size -> {
                BasicText(stringResource(R.string.wizard_finished, flow.title), style = type.title1.copy(color = GlassColors.TextPrimary))
                BasicText(stringResource(R.string.wizard_finished_desc), style = type.body.copy(color = GlassColors.TextSecondary))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    TvButton(stringResource(R.string.wizard_summary), onSummary, Modifier.focusRequester(primary))
                    TvButton(stringResource(R.string.wizard_back_to_flows), onExit)
                }
            }
            else -> {
                val s = steps[index]
                // Read through revision so edits show at once.
                val path = revision.let { store.menuPath(s.step.id) ?: s.step.menu }
                val value = revision.let { store.value(flow.id, s.step.id) }
                BasicText(
                    stringResource(R.string.wizard_step_of, flow.title, index + 1, steps.size),
                    style = type.caption.copy(color = GlassColors.TextSecondary),
                )
                BasicText(s.step.title, style = type.title2.copy(color = GlassColors.TextPrimary))
                BasicText(s.what, style = type.body.copy(color = GlassColors.TextPrimary))
                InfoRow(stringResource(R.string.wizard_menu_path), path, editable = true) { onEdit("path") }
                InfoRow(stringResource(R.string.wizard_recommended), s.step.recommended, editable = false) {}
                InfoRow(stringResource(R.string.wizard_your_value), value.ifEmpty { "—" }, editable = true) { onEdit("value") }
                Judgement(Status.Pass, stringResource(R.string.wizard_correct), s.correct)
                Judgement(Status.Fail, stringResource(R.string.wizard_wrong), s.wrong)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    TvButton(stringResource(R.string.wizard_done), { onResult(StepStatus.Done) }, Modifier.focusRequester(primary))
                    TvButton(stringResource(R.string.wizard_skip), { onResult(StepStatus.Skipped) })
                    TvButton(stringResource(R.string.wizard_not_found), { onResult(StepStatus.NotFound) })
                    TvButton(stringResource(R.string.wizard_hide), onHide)
                }
            }
        }
    }
    LaunchedEffect(index) { runCatching { primary.requestFocus() } }
}

@Composable
private fun InfoRow(label: String, value: String, editable: Boolean, onClick: () -> Unit) {
    val type = LocalGlassType.current
    FocusCard(Modifier.fillMaxWidth(), shape = Shapes.Row, focusedScale = 1.02f, enabled = editable, onClick = onClick) { focused ->
        val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
        val soft = if (focused) GlassColors.OnFocus.copy(alpha = 0.7f) else GlassColors.TextSecondary
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f), style = type.caption.copy(color = soft))
            if (editable && focused) BasicText(stringResource(R.string.wizard_edit_hint), style = type.caption.copy(color = soft))
        }
        BasicText(value, style = type.callout.copy(color = ink))
    }
}

@Composable
private fun Judgement(status: Status, label: String, text: String) {
    if (text.isBlank()) return
    val type = LocalGlassType.current
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(status, Modifier.padding(top = 5.dp))
        Column(Modifier.padding(start = Spacing.S)) {
            BasicText(label, style = type.caption.copy(color = GlassColors.TextSecondary))
            BasicText(text, style = type.callout.copy(color = GlassColors.TextPrimary))
        }
    }
}
