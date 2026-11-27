package io.github.kesemb2.calibrate.patterns

import io.github.kesemb2.calibrate.diag.Status
import io.github.kesemb2.calibrate.ratetest.RateOutcome
import io.github.kesemb2.calibrate.ratetest.RateResult
import io.github.kesemb2.calibrate.ratetest.RateRules
import io.github.kesemb2.calibrate.ratetest.RateRun
import io.github.kesemb2.calibrate.ratetest.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class RateRulesTest {
    private fun result(fps: Float, observed: Float, request: Request = Request.Explicit, dropped: Int = 0) =
        RateResult(RateRun("clip", fps, request), before = 59.94006f, observed = observed, switchMs = 2000, droppedFrames = dropped)

    @Test
    fun plan() {
        val runs = RateRules.plan(listOf("a" to 23.976025f, "b" to 25f, "c" to 50f))
        assertEquals(4, runs.size)
        assertEquals(Request.Media3Default, runs.last().request)
        assertEquals("a", runs.last().clipId)
    }

    @Test
    fun outcomes() {
        assertEquals(RateOutcome.Exact, RateRules.outcome(result(23.976025f, 23.976025f)))
        assertEquals(RateOutcome.NearlyExact, RateRules.outcome(result(23.976025f, 24.000002f)))
        assertEquals(RateOutcome.Multiple, RateRules.outcome(result(25f, 50f)))
        assertEquals(RateOutcome.NoSwitch, RateRules.outcome(result(23.976025f, 59.94006f)))
        assertEquals(RateOutcome.Wrong, RateRules.outcome(result(25f, 60.000004f)))
        assertEquals(RateOutcome.DefaultNoSwitch, RateRules.outcome(result(23.976025f, 59.94006f, Request.Media3Default)))
        assertEquals(RateOutcome.DefaultSwitched, RateRules.outcome(result(23.976025f, 23.976025f, Request.Media3Default)))
    }

    @Test
    fun statuses() {
        assertEquals(Status.Pass, RateRules.status(result(50f, 50f)))
        assertEquals(Status.Warn, RateRules.status(result(23.976025f, 24.000002f)))
        assertEquals(Status.Fail, RateRules.status(result(23.976025f, 59.94006f)))
        assertEquals(Status.Info, RateRules.status(result(23.976025f, 59.94006f, Request.Media3Default)))
        assertEquals(Status.Warn, RateRules.status(result(50f, 50f, dropped = 30)))
    }
}
