package io.github.kesemb2.calibrate

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
import io.github.kesemb2.calibrate.patterns.PatternStore
import io.github.kesemb2.calibrate.ui.chain.ChainScreen
import io.github.kesemb2.calibrate.ui.chain.ChainState
import io.github.kesemb2.calibrate.ui.home.HomeScreen
import io.github.kesemb2.calibrate.ui.patterns.DecoderSummary
import io.github.kesemb2.calibrate.ui.patterns.PatternsScreen
import io.github.kesemb2.calibrate.ui.patterns.PlayerScreen
import io.github.kesemb2.calibrate.ui.patterns.summarize
import io.github.kesemb2.calibrate.ui.theme.GlassTypography
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens, newest last. The UI language follows the TV (values-iw for Hebrew, RTL from the locale). */
sealed interface Destination {
    data object Home : Destination
    data object Chain : Destination
    data object Patterns : Destination
    data class Player(val index: Int) : Destination
}

class MainActivity : ComponentActivity() {

    private val scope = MainScope()
    private val stack = mutableStateOf<List<Destination>>(listOf(Destination.Home))
    private val chain = mutableStateOf<ChainState?>(null)
    private val decoder = mutableStateOf<DecoderSummary?>(null)
    private lateinit var patterns: PatternStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = GlassTypography.create(this)
        patterns = PatternStore(applicationContext)
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
                        onOpenPatterns = {
                            // A failed download (no network, release not published yet) retries on every visit.
                            if (patterns.state.value is PatternStore.State.Failed) loadPatterns()
                            open(Destination.Patterns)
                        },
                    )
                    Destination.Chain -> ChainScreen(chain.value, onRerun = ::runChainCheck)
                    Destination.Patterns -> PatternsScreen(
                        state = patternState,
                        decoder = decoder.value,
                        onRetry = ::loadPatterns,
                        onOpen = { open(Destination.Player(it)) },
                    )
                    is Destination.Player -> {
                        val ready = patternState as? PatternStore.State.Ready
                        if (ready != null) {
                            PlayerScreen(ready.manifest.patterns.map { it to patterns.file(it) }, d.index)
                        }
                    }
                }
            }
        }
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
                ready.manifest.patterns.map { p ->
                    Log.i(TAG, "decoder ${p.id}: checking")
                    DecoderCheck.check(patterns.file(p), p.probes).also { r ->
                        Log.i(TAG, "decoder ${p.id}: ${r.decoder} checked=${r.checked} mismatches=${r.mismatches} error=${r.error}")
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
