package io.github.kesemb2.calibrate.ui.patterns

import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.patterns.PatternCatalog
import io.github.kesemb2.calibrate.patterns.PatternFile
import io.github.kesemb2.calibrate.ratetest.clearFrameRate
import io.github.kesemb2.calibrate.ratetest.requestFrameRate
import io.github.kesemb2.calibrate.ui.components.glassSurface
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Shapes
import io.github.kesemb2.calibrate.ui.theme.Spacing
import java.io.File

/**
 * Full-screen pattern player. The video goes to a SurfaceView, which the box composes on its hardware video plane at
 * the output resolution (3840 × 2160), separate from the 1080p UI layer: that is what keeps the pattern pixel exact
 * and lets HDR metadata reach the TV. While the instructions are hidden the screen is 100 % video.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(patterns: List<Pair<PatternFile, File>>, startIndex: Int) {
    val context = LocalContext.current
    val type = LocalGlassType.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var index by remember { mutableIntStateOf(startIndex) }
    var showInfo by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            // Static patterns: keep the current output mode instead of switching the TV to 23.976 Hz for each one.
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val surfaceBox = remember { arrayOfNulls<Surface>(1) }
    LaunchedEffect(index) {
        error = null
        val pattern = patterns[index].first
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(patterns[index].second)))
        player.prepare()
        // Moving patterns are judged at their own rate (24p judder); static ones keep the current mode, so browsing
        // them never costs an HDMI resync.
        while (surfaceBox[0] == null) kotlinx.coroutines.delay(50)
        surfaceBox[0]?.let { if (pattern.motion) requestFrameRate(it, pattern.fps) else clearFrameRate(it) }
    }
    DisposableEffect(Unit) { onDispose { surfaceBox[0]?.let { clearFrameRate(it) } } }

    fun step(delta: Int) {
        index = (index + delta + patterns.size) % patterns.size
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyUp) showInfo = !showInfo
                        true
                    }
                    // Arrows follow the reading direction: "next" is to the left in Hebrew.
                    Key.DirectionRight -> {
                        if (e.type == KeyEventType.KeyDown) step(if (rtl) -1 else 1)
                        true
                    }
                    Key.DirectionLeft -> {
                        if (e.type == KeyEventType.KeyDown) step(if (rtl) 1 else -1)
                        true
                    }
                    else -> false
                }
            },
    ) {
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
        val current = patterns[index].first
        val text = PatternCatalog.text(current)
        AnimatedVisibility(
            visible = showInfo || error != null,
            enter = fadeIn(spring(stiffness = 400f)),
            exit = fadeOut(spring(stiffness = 400f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.ScreenVertical),
        ) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    // Dark glass: the panel floats over white patterns as well as black ones.
                    .glassSurface(Shapes.Panel, tint = Color(0xD90B0D14))
                    .padding(Spacing.XXL),
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text?.let { stringResource(it.title) } ?: current.id,
                        Modifier.weight(1f),
                        style = type.title2.copy(color = GlassColors.TextPrimary),
                    )
                    PatternCatalog.formatLabel(current.format)?.let {
                        BasicText(stringResource(it), style = type.caption.copy(color = GlassColors.TextSecondary))
                    }
                }
                error?.let {
                    BasicText(stringResource(R.string.player_error, it), style = type.body.copy(color = Color(0xFFFF453A)))
                }
                text?.let { BasicText(stringResource(it.howto), style = type.body.copy(color = GlassColors.TextPrimary)) }
                Row {
                    BasicText(stringResource(R.string.player_hint), Modifier.weight(1f), style = type.caption.copy(color = GlassColors.TextSecondary))
                    BasicText(
                        stringResource(R.string.pattern_count, index + 1, patterns.size),
                        style = type.caption.copy(color = GlassColors.TextSecondary),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
