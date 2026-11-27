package io.github.kesemb2.calibrate.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksTest {

    private fun modes(vararg fps: Float) = fps.map { DisplayModeInfo(3840, 2160, it) }

    /** Worst case the checks must survive: the API showing one mode and no HDR while the HDMI driver sees everything. */
    private val homatics = ChainSnapshot(
        apiActiveMode = Read.Ok(DisplayModeInfo(3840, 2160, 59.94006f)),
        apiModes = Read.Ok(modes(59.94006f)),
        apiHdrTypes = Read.Ok(emptySet()),
        apiLuminance = Read.Ok(Luminance(500f, 500f, 0f)),
        matchContent = Read.Ok(MatchContent.Always),
        hdrConversion = Read.Ok(HdrConversion.Passthrough),
        userDisabledHdr = Read.Ok(""),
        hdmiTx = Read.Ok(
            HdmiTxInfo(
                hdrTypes = setOf(HdrType.Hdr10, HdrType.Hlg, HdrType.Hdr10Plus, HdrType.DolbyVision),
                luminance = Luminance(0f, 0f, 0f),
                dvModes = listOf("DV_RGB_444_8BIT", "DV_YCbCr_422_12BIT", "LL_YCbCr_422_12BIT"),
                dv4k60 = true,
                outputAttr = "444,8bit",
                modes = modes(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f),
            ),
        ),
        sysfsMode = Read.Ok("2160p60hz"),
        decoders = Read.Ok(DecoderInfo(true, true, true, setOf(5, 8), true, true)),
        sinkName = "Mi TV (XMD)",
        build = "test",
    )

    private fun result(s: ChainSnapshot, id: CheckId) = Checks.evaluate(s).first { it.id == id }

    @Test
    fun homaticsAsMeasured() {
        assertEquals(Outcome.Res4k, result(homatics, CheckId.Resolution).outcome)
        assertEquals(Outcome.RatesAll, result(homatics, CheckId.RefreshRates).outcome)
        assertEquals(Outcome.Hdr10Ok, result(homatics, CheckId.Hdr10).outcome)
        assertEquals(Outcome.DvOkHidden, result(homatics, CheckId.DolbyVision).outcome)
        assertEquals(Outcome.LumEmpty, result(homatics, CheckId.EdidLuminance).outcome)
        assertEquals(Outcome.MatchAlways, result(homatics, CheckId.MatchFrameRate).outcome)
        assertEquals(Outcome.ConvPassthrough, result(homatics, CheckId.HdrConversion).outcome)
        assertEquals("YCbCr 4:4:4, 8-bit", result(homatics, CheckId.HdmiOutput).value)
        assertEquals(Status.Pass, result(homatics, CheckId.Decoders).status)
    }

    @Test
    fun dolbyVisionMissingEverywhereIsRed() {
        val s = homatics.copy(
            hdmiTx = Read.Ok((homatics.hdmiTx as Read.Ok).value.copy(hdrTypes = setOf(HdrType.Hdr10), dvModes = emptyList())),
        )
        val dv = result(s, CheckId.DolbyVision)
        assertEquals(Status.Fail, dv.status)
        assertEquals(Outcome.DvMissing, dv.outcome)
    }

    @Test
    fun sysfsBlockedAndApiHidingHdrIsRed() {
        val s = homatics.copy(hdmiTx = Read.Failed("FileNotFoundException: EACCES"))
        assertEquals(Status.Fail, result(s, CheckId.Hdr10).status)
        assertEquals(Outcome.RatesUnknown, result(s, CheckId.RefreshRates).outcome)
        // Android's substitute luminance is the only one left: reported, but not claimed as the TV's.
        assertEquals(Outcome.LumDefault, result(s, CheckId.EdidLuminance).outcome)
        val real = s.copy(apiLuminance = Read.Ok(Luminance(1200f, 600f, 0.05f)))
        assertEquals(Outcome.LumOk, result(real, CheckId.EdidLuminance).outcome)
    }

    /** What the Homatics really returns to an app (stage 0 report, 2026-11-27): full API, sysfs blocked. */
    @Test
    fun homaticsAsReportedByTheApp() {
        val s = homatics.copy(
            apiModes = Read.Ok(modes(59.94f, 60f, 50f, 29.97f, 30f, 25f, 23.976f, 24f)),
            apiHdrTypes = Read.Ok(HdrType.entries.toSet()),
            apiLuminance = Read.Ok(Luminance(1000f, 1000f, 0f)),
            hdmiTx = Read.Failed("FileNotFoundException: EACCES"),
        )
        assertEquals(Outcome.RatesAll, result(s, CheckId.RefreshRates).outcome)
        assertEquals(Outcome.DvOk, result(s, CheckId.DolbyVision).outcome)
        assertEquals(Outcome.LumDefault, result(s, CheckId.EdidLuminance).outcome)
        assertEquals(Outcome.OutUnknown, result(s, CheckId.HdmiOutput).outcome)
    }

    @Test
    fun apiWithEverything() {
        val s = homatics.copy(
            apiModes = Read.Ok(modes(23.976f, 24f, 25f, 50f, 59.94f, 60f)),
            apiHdrTypes = Read.Ok(HdrType.entries.toSet()),
            hdmiTx = Read.Failed("blocked"),
        )
        assertEquals(Outcome.DvOk, result(s, CheckId.DolbyVision).outcome)
        assertEquals(Outcome.RatesAll, result(s, CheckId.RefreshRates).outcome)
    }

    @Test
    fun missingRates() {
        val no24 = homatics.copy(apiModes = Read.Ok(modes(50f, 59.94f, 60f)), hdmiTx = Read.Failed("x"))
        assertEquals(Status.Fail, result(no24, CheckId.RefreshRates).status)
        val noPal = homatics.copy(apiModes = Read.Ok(modes(23.976f, 24f, 59.94f, 60f)), hdmiTx = Read.Failed("x"))
        assertEquals(Outcome.RatesNoPal, result(noPal, CheckId.RefreshRates).outcome)
    }

    @Test
    fun frameRateAndConversionSettings() {
        assertEquals(Status.Fail, result(homatics.copy(matchContent = Read.Ok(MatchContent.Never)), CheckId.MatchFrameRate).status)
        assertEquals(Status.Warn, result(homatics.copy(matchContent = Read.Ok(MatchContent.SeamlessOnly)), CheckId.MatchFrameRate).status)
        assertEquals(Status.Warn, result(homatics.copy(matchContent = Read.Failed("x")), CheckId.MatchFrameRate).status)
        assertEquals(Status.Fail, result(homatics.copy(hdrConversion = Read.Ok(HdrConversion.Force)), CheckId.HdrConversion).status)
    }

    @Test
    fun disabledHdrFormats() {
        val r = result(homatics.copy(userDisabledHdr = Read.Ok("1,4")), CheckId.DisabledHdr)
        assertEquals(Status.Fail, r.status)
        assertEquals("Dolby Vision, HDR10+", r.value)
    }

    @Test
    fun attrDescriptions() {
        assertEquals("YCbCr 4:2:2, 12-bit", Checks.describeAttr("422,12bit"))
        assertEquals("RGB, 8-bit", Checks.describeAttr("rgb,8bit"))
        assertEquals("weird", Checks.describeAttr("weird"))
    }

    @Test
    fun fpsFormatting() {
        assertEquals("23.976", formatFps(23.976025f))
        assertEquals("24", formatFps(24.000002f))
        assertEquals("59.94", formatFps(59.94006f))
    }
}
