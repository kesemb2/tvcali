package io.github.kesemb2.calibrate.ratetest

import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.diag.formatFps
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a run asks for the frame rate:
 * [Explicit] = Surface.setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS), what a dedicated video player does;
 * [Media3Default] = ExoPlayer's default (only if seamless), what most streaming apps built on it do.
 */
enum class Request { Explicit, Media3Default }

data class RateRun(val clipId: String, val fps: Float, val request: Request)

data class RateResult(
    val run: RateRun,
    val before: Float,
    val observed: Float,
    /** Time from start of playback to the display reaching [observed]; null when it never changed. */
    val switchMs: Long?,
    val droppedFrames: Int,
)

enum class RateOutcome { Exact, NearlyExact, Multiple, NoSwitch, Wrong, DefaultNoSwitch, DefaultSwitched }

object RateRules {

    /** The runs of one test: each clip with an explicit request, plus 23.976 the way most apps ask. */
    fun plan(clips: List<Pair<String, Float>>): List<RateRun> {
        val explicit = clips.map { (id, fps) -> RateRun(id, fps, Request.Explicit) }
        val film = clips.firstOrNull { abs(it.second - 23.976f) < 0.01f }
        return explicit + listOfNotNull(film?.let { RateRun(it.first, it.second, Request.Media3Default) })
    }

    fun outcome(r: RateResult): RateOutcome {
        val switched = abs(r.observed - r.before) > 0.01f
        if (r.run.request == Request.Media3Default) {
            return if (switched && isGood(r.observed, r.run.fps)) RateOutcome.DefaultSwitched else RateOutcome.DefaultNoSwitch
        }
        return when {
            abs(r.observed - r.run.fps) < 0.01f -> RateOutcome.Exact
            // 23.976 content on 24.000 Hz repeats one frame every ~42 s.
            abs(r.observed - r.run.fps) < 0.05f -> RateOutcome.NearlyExact
            isMultiple(r.observed, r.run.fps) -> RateOutcome.Multiple
            !switched -> RateOutcome.NoSwitch
            else -> RateOutcome.Wrong
        }
    }

    fun status(r: RateResult): Status {
        val base = when (outcome(r)) {
            RateOutcome.Exact, RateOutcome.Multiple, RateOutcome.DefaultSwitched -> Status.Pass
            RateOutcome.NearlyExact -> Status.Warn
            RateOutcome.DefaultNoSwitch -> Status.Info
            RateOutcome.NoSwitch, RateOutcome.Wrong -> Status.Fail
        }
        // A handful of drops right after a mode change is normal; steady dropping is not.
        return if (base == Status.Pass && r.droppedFrames > 5) Status.Warn else base
    }

    fun describe(r: RateResult): String {
        val ms = r.switchMs?.let { " · ${(it / 100.0).roundToInt() / 10.0} s" } ?: ""
        return "${formatFps(r.run.fps)} fps → ${formatFps(r.observed)} Hz$ms · ${r.droppedFrames} dropped"
    }

    private fun isGood(observed: Float, fps: Float) = abs(observed - fps) < 0.05f || isMultiple(observed, fps)

    /** 25 fps on 50 Hz shows every frame twice, evenly: as good as an exact match. */
    private fun isMultiple(observed: Float, fps: Float): Boolean {
        val k = (observed / fps).roundToInt()
        return k >= 2 && abs(observed - k * fps) < 0.01f * k
    }
}
