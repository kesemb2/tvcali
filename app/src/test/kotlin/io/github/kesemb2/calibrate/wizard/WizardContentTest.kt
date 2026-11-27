package io.github.kesemb2.calibrate.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WizardContentTest {

    /** The real content shipped in the app. */
    private val content = WizardContent.parse(File("src/main/assets/wizard.json").readText())

    @Test
    fun everyFlowStepExists() {
        content.flows.forEach { flow ->
            assertEquals(flow.id, flow.stepIds.size, content.stepsOf(flow).size)
        }
        assertEquals(listOf("sdr", "hdr10", "hdr10plus", "dv"), content.flows.map { it.id })
    }

    /** Playing another format's pattern would switch the TV to another picture mode mid-flow. */
    @Test
    fun flowsOnlyPlayTheirOwnFormat() {
        val prefix = mapOf("sdr" to "sdr_", "hdr10" to "hdr_", "hdr10plus" to "hdrp_", "dv81" to "dv_")
        content.flows.forEach { flow ->
            content.stepsOf(flow).forEach { step ->
                assertTrue("${flow.id}/${step.step.id} plays ${step.patternId}", step.patternId.startsWith(prefix.getValue(flow.format)))
            }
        }
    }

    @Test
    fun patternMapping() {
        val dv = content.flow("dv")!!
        assertEquals("dv_subtitles", WizardContent.patternFor(dv, content.steps.getValue("local_dimming")))
        val sdr = content.flow("sdr")!!
        assertEquals("sdr_overscan", WizardContent.patternFor(sdr, content.steps.getValue("local_dimming")))
        val hdrp = content.flow("hdr10plus")!!
        // An SDR-only step falls back to the flow's own start pattern.
        assertEquals("hdrp_grayscale", WizardContent.patternFor(hdrp, content.steps.getValue("sharpness")))
    }

    @Test
    fun hdrOverridesApply() {
        val hdr = content.flow("hdr10")!!
        val sharp = content.resolve(hdr, content.steps.getValue("sharpness"))
        assertTrue(sharp.what.contains("SDR"))
        val sdrSharp = content.resolve(content.flow("sdr")!!, content.steps.getValue("sharpness"))
        assertEquals(content.steps.getValue("sharpness").what, sdrSharp.what)
    }
}
