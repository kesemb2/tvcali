package io.github.kesemb2.calibrate.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    private val json = """
        {"version": 4, "width": 3840, "height": 2160, "patterns": [
          {"id": "sdr_pluge", "file": "sdr_pluge.mp4", "size": 10, "sha256": "ab", "format": "sdr",
           "probes": [{"name": "black", "x": 1, "y": 2, "w": 3, "h": 4, "y10": 64}]},
          {"id": "hdr_pan_24p", "file": "hdr_pan_24p.mp4", "size": 20, "sha256": "cd", "format": "hdr10",
           "role": "pattern", "motion": true, "fps_num": 24000, "fps_den": 1001, "probes": []},
          {"id": "test_rate_25", "file": "test_rate_25.mp4", "size": 30, "sha256": "ef", "format": "sdr",
           "role": "ratetest", "motion": true, "fps_num": 25, "fps_den": 1, "probes": []}
        ]}
    """.trimIndent()

    @Test
    fun parsesRolesAndRates() {
        val m = Manifest.parse(json)
        assertEquals(4, m.version)
        assertEquals(listOf("sdr_pluge", "hdr_pan_24p"), m.viewable.map { it.id })
        assertEquals(listOf("test_rate_25"), m.rateTests.map { it.id })
        assertEquals(25f, m.rateTests[0].fps, 1e-6f)
        assertEquals(23.976f, m.viewable[1].fps, 1e-3f)
        assertTrue(m.viewable[1].motion)
        assertFalse(m.viewable[0].motion)
        assertEquals(Probe("black", 1, 2, 3, 4, 64), m.patterns[0].probes[0])
    }

    /** Version 1-3 manifests had no role/motion/fps fields. */
    @Test
    fun oldManifestDefaults() {
        val m = Manifest.parse("""{"version": 3, "width": 3840, "height": 2160, "patterns": [
            {"id": "a", "file": "a.mp4", "size": 1, "sha256": "x", "format": "sdr"}]}""")
        assertEquals("pattern", m.patterns[0].role)
        assertFalse(m.patterns[0].motion)
    }
}
