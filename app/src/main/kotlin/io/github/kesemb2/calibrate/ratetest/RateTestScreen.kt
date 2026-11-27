package io.github.kesemb2.calibrate.ratetest

import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.diag.formatFps
import io.github.kesemb2.calibrate.ui.components.glassSurface
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

private const val SWITCH_WAIT_MS = 7_000L
private const val SETTLE_MS = 800L
private const val MEASURE_MS = 3_000L
private const val RESTORE_WAIT_MS = 5_000L

/**
 * Plays each clip of [runs] in turn and watches the display mode, the way a real player would ask for it. HDMI mode
 * changes blank the screen for a moment; that is expected. Reports through [onDone].
 */
@OptIn(UnstableApi::class)
@Composable
fun RateTestScreen(runs: List<Pair<RateRun, File>>, onDone: (List<RateResult>) -> Unit) {
    val context = LocalContext.current
    val type = LocalGlassType.current
    val done by rememberUpdatedState(onDone)
    val display: Display? = remember {
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    }
    var step by remember { mutableIntStateOf(0) }
    var currentHz by remember { mutableFloatStateOf(display?.mode?.refreshRate ?: 0f) }
    val surfaceBox = remember { arrayOfNulls<Surface>(1) }
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            surfaceBox[0]?.let { clearFrameRate(it) }
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        // Wait for the SurfaceView's surface.
        while (surfaceBox[0] == null) delay(50)
        val results = mutableListOf<RateResult>()
        val home = display?.mode?.refreshRate ?: 0f
        for ((i, pair) in runs.withIndex()) {
            val (run, file) = pair
            step = i
            val before = display?.mode?.refreshRate ?: 0f
            player.videoChangeFrameRateStrategy = when (run.request) {
                Request.Media3Default -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                Request.Explicit -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            while (player.playbackState != Player.STATE_READY) delay(50)
            val start = SystemClock.elapsedRealtime()
            if (run.request == Request.Explicit) surfaceBox[0]?.let { requestFrameRate(it, run.fps) }

            var switchMs: Long? = null
            while (SystemClock.elapsedRealtime() - start < SWITCH_WAIT_MS) {
                val hz = display?.mode?.refreshRate ?: 0f
                currentHz = hz
                if (abs(hz - before) > 0.01f && switchMs == null) switchMs = SystemClock.elapsedRealtime() - start
                if (switchMs != null && SystemClock.elapsedRealtime() - start > switchMs + SETTLE_MS) break
                delay(100)
            }
            // Count dropped frames only once the mode has settled: a few drops during the HDMI resync are normal.
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            val droppedBefore = counters?.droppedBufferCount ?: 0
            delay(MEASURE_MS)
            counters?.ensureUpdated()
            val dropped = (counters?.droppedBufferCount ?: 0) - droppedBefore
            val observed = display?.mode?.refreshRate ?: 0f
            currentHz = observed
            results += RateResult(run, before, observed, switchMs, dropped)

            player.stop()
            if (run.request == Request.Explicit) surfaceBox[0]?.let { clearFrameRate(it) }
            // Let the display return to its own mode, so the next run starts from the same place.
            val back = SystemClock.elapsedRealtime()
            while (abs((display?.mode?.refreshRate ?: home) - home) > 0.01f &&
                SystemClock.elapsedRealtime() - back < RESTORE_WAIT_MS
            ) {
                currentHz = display?.mode?.refreshRate ?: 0f
                delay(100)
            }
        }
        done(results)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceBox[0] = holder.surface
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceBox[0] = null
                        }
                    })
                    player.setVideoSurfaceView(sv)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val (run, _) = runs[step]
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = Spacing.ScreenVertical)
                .widthIn(max = 640.dp)
                .glassSurface(Shapes.Panel, tint = Color(0xD90B0D14))
                .padding(Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            BasicText(
                stringResource(R.string.rate_running, step + 1, runs.size),
                style = type.headline.copy(color = GlassColors.TextPrimary),
            )
            BasicText(
                stringResource(
                    if (run.request == Request.Explicit) R.string.rate_request_explicit else R.string.rate_request_default,
                    formatFps(run.fps),
                ),
                style = type.callout.copy(color = GlassColors.TextSecondary),
            )
            BasicText(
                stringResource(R.string.rate_display_now, formatFps(currentHz)),
                style = type.callout.copy(color = GlassColors.TextSecondary),
            )
            BasicText(stringResource(R.string.rate_blank_note), style = type.caption.copy(color = GlassColors.TextTertiary))
        }
    }
}

/** Ask the display for [fps] even if switching blanks the screen (what a dedicated video player does). */
fun requestFrameRate(surface: Surface, fps: Float) {
    if (!surface.isValid) return
    if (Build.VERSION.SDK_INT >= 31) {
        surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
    } else if (Build.VERSION.SDK_INT >= 30) {
        surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}

fun clearFrameRate(surface: Surface) {
    if (!surface.isValid) return
    if (Build.VERSION.SDK_INT >= 31) {
        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
    } else if (Build.VERSION.SDK_INT >= 30) {
        surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }
}
