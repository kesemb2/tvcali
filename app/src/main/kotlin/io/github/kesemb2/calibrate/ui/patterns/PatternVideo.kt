package io.github.kesemb2.calibrate.ui.patterns

import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.kesemb2.calibrate.patterns.PatternFile
import io.github.kesemb2.calibrate.ratetest.clearFrameRate
import io.github.kesemb2.calibrate.ratetest.requestFrameRate
import kotlinx.coroutines.delay
import java.io.File

/**
 * A pattern looping full screen on the video plane (pixel exact, HDR metadata intact), for use behind other UI.
 * Changing [pattern] switches the file without recreating the player; moving patterns ask for their frame rate.
 */
@OptIn(UnstableApi::class)
@Composable
fun PatternVideo(pattern: PatternFile?, file: File?, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val surfaceBox = remember { arrayOfNulls<Surface>(1) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            playWhenReady = true
        }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            surfaceBox[0]?.let { clearFrameRate(it) }
            player.release()
        }
    }
    LaunchedEffect(file?.path) {
        if (pattern == null || file == null) {
            player.stop()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        while (surfaceBox[0] == null) delay(50)
        surfaceBox[0]?.let { if (pattern.motion) requestFrameRate(it, pattern.fps) else clearFrameRate(it) }
    }
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
        modifier = modifier,
    )
}
