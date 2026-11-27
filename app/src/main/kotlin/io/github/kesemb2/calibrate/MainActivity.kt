package io.github.kesemb2.calibrate

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import io.github.kesemb2.calibrate.diag.ChainProbe
import io.github.kesemb2.calibrate.diag.Checks
import io.github.kesemb2.calibrate.diag.Report
import io.github.kesemb2.calibrate.patterns.DecoderCheck
import io.github.kesemb2.calibrate.patterns.PatternFormats
import io.github.kesemb2.calibrate.patterns.PatternStore
import io.github.kesemb2.calibrate.ratetest.RateResult
import io.github.kesemb2.calibrate.ratetest.RateRules
import io.github.kesemb2.calibrate.ratetest.RateTestScreen
import io.github.kesemb2.calibrate.ui.chain.ChainScreen
import io.github.kesemb2.calibrate.ui.chain.ChainState
import io.github.kesemb2.calibrate.ui.home.HomeScreen
import io.github.kesemb2.calibrate.ui.patterns.DecoderSummary
import io.github.kesemb2.calibrate.ui.patterns.PatternsScreen
import io.github.kesemb2.calibrate.ui.patterns.PlayerScreen
import io.github.kesemb2.calibrate.ui.patterns.summarize
import io.github.kesemb2.calibrate.ui.theme.GlassTypography
import io.github.kesemb2.calibrate.ui.wizard.SummaryScreen
import io.github.kesemb2.calibrate.ui.wizard.WizardFlowScreen
import io.github.kesemb2.calibrate.ui.wizard.WizardHomeScreen
import io.github.kesemb2.calibrate.wizard.WizardContent
import io.github.kesemb2.calibrate.wizard.WizardStore
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens, newest last. The UI is always Hebrew (AppLocale). */
sealed interface Destination {
    data object Home : Destination
    data object Chain : Destination
    data object Patterns : Destination
    data class Player(val format: String, val index: Int) : Destination
    data object RateTest : Destination
    data object Wizard : Destination
    data class WizardFlow(val id: String) : Destination
    data object WizardSummary : Destination
}

class MainActivity : ComponentActivity() {

    /** Always Hebrew and right to left (see AppLocale). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val scope = MainScope()
    private val stack = mutableStateOf<List<Destination>>(listOf(Destination.Home))
    private val chain = mutableStateOf<ChainState?>(null)
    private val decoder = mutableStateOf<DecoderSummary?>(null)
    private val rateResults = mutableStateOf<List<RateResult>?>(null)
    private val patternFormat = mutableStateOf(PatternFormats.SDR)
    private lateinit var patterns: PatternStore
    private lateinit var wizardStore: WizardStore
    private val wizard: WizardContent by lazy { WizardContent.parse(assets.open("wizard.json").bufferedReader().readText()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = GlassTypography.create(this)
        patterns = PatternStore(applicationContext)
        wizardStore = WizardStore(applicationContext)
        // First start downloads the patterns in the background; later starts find them on disk.
        loadPatterns()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.value.size > 1) stack.value = stack.value.dropLast(1) else finish()
            }
        })

        setContent {
            CompositionLocalProvider(LocalGlassType provides type) {
                val patternState by patterns.state.collectAsState()
                when (val d = stack.value.last()) {
                    Destination.Home -> HomeScreen(
                        onOpenChain = { open(Destination.Chain); runChainCheck() },
                        onOpenWizard = { open(Destination.Wizard) },
                        onOpenPatterns = {
                            // A failed download (no network, release not published yet) retries on every visit.
                            if (patterns.state.value is PatternStore.State.Failed) loadPatterns()
                            open(Destination.Patterns)
                        },
                    )
                    Destination.Chain -> ChainScreen(
                        state = chain.value,
                        onRerun = ::runChainCheck,
                        rateResults = rateResults.value,
                        rateAvailable = (patternState as? PatternStore.State.Ready)?.manifest?.rateTests?.isNotEmpty() == true,
                        onRunRateTest = { open(Destination.RateTest) },
                    )
                    Destination.Wizard -> WizardHomeScreen(
                        content = wizard,
                        store = wizardStore,
                        patternsReady = patternState is PatternStore.State.Ready,
                        onOpenFlow = { open(Destination.WizardFlow(it)) },
                        onSummary = { open(Destination.WizardSummary) },
                    )
                    is Destination.WizardFlow -> {
                        val ready = patternState as? PatternStore.State.Ready
                        val flow = wizard.flow(d.id)
                        if (ready != null && flow != null) {
                            WizardFlowScreen(
                                content = wizard,
                                flow = flow,
                                store = wizardStore,
                                patternOf = { id -> ready.manifest.patterns.firstOrNull { it.id == id }?.let { it to patterns.file(it) } },
                                onExit = { back() },
                                onSummary = { back(); open(Destination.WizardSummary) },
                            )
                        }
                    }
                    Destination.WizardSummary -> SummaryScreen(wizard, wizardStore)
                    Destination.RateTest -> {
                        val ready = patternState as? PatternStore.State.Ready
                        if (ready != null) {
                            val clips = ready.manifest.rateTests
                            val runs = RateRules.plan(clips.map { it.id to it.fps })
                                .map { run -> run to patterns.file(clips.first { it.id == run.clipId }) }
                            RateTestScreen(runs) { results ->
                                rateResults.value = results
                                results.forEach { Log.i(TAG, "rate ${RateRules.status(it)} ${it.run.request}: ${RateRules.describe(it)}") }
                                if (stack.value.lastOrNull() == Destination.RateTest) stack.value = stack.value.dropLast(1)
                            }
                        }
                    }
                    Destination.Patterns -> PatternsScreen(
                        state = patternState,
                        decoder = decoder.value,
                        onRetry = ::loadPatterns,
                        format = patternFormat.value,
                        onFormat = { patternFormat.value = it },
                        onOpen = { f, i -> open(Destination.Player(f, i)) },
                    )
                    is Destination.Player -> {
                        val ready = patternState as? PatternStore.State.Ready
                        if (ready != null) {
                            PlayerScreen(ready.manifest.ofFormat(d.format).map { it to patterns.file(it) }, d.index)
                        }
                    }
                }
            }
        }
    }

    private fun back() {
        if (stack.value.size > 1) stack.value = stack.value.dropLast(1)
    }

    private fun open(destination: Destination) {
        stack.value = stack.value + destination
    }

    private fun loadPatterns() {
        scope.launch {
            patterns.ensure()
            val ready = patterns.state.value as? PatternStore.State.Ready ?: return@launch
            if (decoder.value != null) return@launch
            val results = withContext(Dispatchers.IO) {
                ready.manifest.patterns.filter { it.probes.isNotEmpty() }.map { p ->
                    Log.i(TAG, "decoder ${p.id}: checking")
                    DecoderCheck.check(patterns.file(p), p.probes).also { r ->
                        Log.i(TAG, "decoder ${p.id}: ${r.decoder} bits=${r.bits} checked=${r.checked} mismatches=${r.mismatches} error=${r.error}")
                    }
                }
            }
            decoder.value = summarize(results)
        }
    }

    private fun runChainCheck() {
        chain.value = null
        scope.launch {
            val (snapshot, results) = withContext(Dispatchers.IO) {
                val snapshot = ChainProbe.collect(applicationContext)
                snapshot to Checks.evaluate(snapshot)
            }
            chain.value = ChainState(results, Report.sources(snapshot))
            // Readable from a computer with: adb logcat -d -s Calibrate
            Report.text(snapshot, results).lines().forEach { Log.i(TAG, it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Calibrate"
    }
}
