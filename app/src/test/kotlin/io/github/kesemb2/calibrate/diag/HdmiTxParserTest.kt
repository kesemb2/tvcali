package io.github.kesemb2.calibrate.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are the real sysfs output of the Homatics Box R 4K Plus behind the Hue Sync Box 8K (2026-11-27). */
class HdmiTxParserTest {

    private val hdrCap = """
        HDR10Plus Supported: 1
        HDR Static Metadata:
            Supported EOTF:
                Traditional SDR: 1
                Traditional HDR: 0
                SMPTE ST 2084: 1
                Hybrid Log-Gamma: 1
            Supported SMD type1: 1
            Luminance Data
                Max: 0
                Avg: 0
                Min: 0

        HDR Dynamic Metadata:

        colorimetry_data: 0
    """.trimIndent()

    private val dvCap = """
        DolbyVision RX support list:
        VSVDB Version: V2
        2160p60hz: 1
        Parity: 1
        Support mode:
          DV_RGB_444_8BIT
          DV_YCbCr_422_12BIT
          LL_YCbCr_422_12BIT
        IEEEOUI: 0x00d046
        EMP: 0
        VSVDB: eb0146d0004d1a768e4c5f8f
    """.trimIndent()

    @Test
    fun hdrCapFromTheBox() {
        val (types, lum) = HdmiTxParser.parseHdrCap(hdrCap)
        assertEquals(setOf(HdrType.Hdr10, HdrType.Hlg, HdrType.Hdr10Plus), types)
        assertEquals(Luminance(0f, 0f, 0f), lum)
        assertTrue(lum!!.isEmpty)
    }

    @Test
    fun dvCapFromTheBox() {
        val (modes, fourK60) = HdmiTxParser.parseDvCap(dvCap)!!
        assertEquals(listOf("DV_RGB_444_8BIT", "DV_YCbCr_422_12BIT", "LL_YCbCr_422_12BIT"), modes)
        assertTrue(fourK60)
    }

    @Test
    fun dvCapWithoutDolbyVision() {
        assertNull(HdmiTxParser.parseDvCap("The Rx don't support DolbyVision"))
        assertNull(HdmiTxParser.parseDvCap(""))
    }

    @Test
    fun hdrCapWithoutHdr() {
        val (types, _) = HdmiTxParser.parseHdrCap("HDR10Plus Supported: 0\nSMPTE ST 2084: 0\nHybrid Log-Gamma: 0")
        assertTrue(types.isEmpty())
    }

    @Test
    fun dispCap() {
        val modes = HdmiTxParser.parseDispCap("480p60hz\n1080p60hz\n2160p24hz\n2160p25hz\n2160p50hz\n2160p60hz*\n")
        assertEquals(4, modes.count { it.is4k() })
        assertTrue(modes.any { it.is4k() && it.matches(24f) })
        assertFalse(modes.any { it.width == 0 })
    }
}
