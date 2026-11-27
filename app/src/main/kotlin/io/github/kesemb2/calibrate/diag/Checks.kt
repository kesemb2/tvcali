package io.github.kesemb2.calibrate.diag

import kotlin.math.abs

enum class Status { Pass, Warn, Fail, Info }

enum class CheckId {
    Resolution, RefreshRates, Hdr10, DolbyVision, OtherHdr, EdidLuminance,
    MatchFrameRate, HdrConversion, DisabledHdr, HdmiOutput, Decoders,
}

/** What a check found; each outcome has its own explanation (and fix) in the strings. */
enum class Outcome {
    Res4k, ResLow, ResUnknown,
    RatesAll, RatesNo24, RatesNoPal, RatesUnknown,
    Hdr10Ok, Hdr10Missing,
    DvOk, DvOkHidden, DvMissing,
    OtherHdrInfo,
    LumOk, LumEmpty, LumDefault, LumUnknown,
    MatchAlways, MatchSeamless, MatchNever, MatchUnknown,
    ConvPassthrough, ConvSystem, ConvForce, ConvUnknown,
    DisabledNone, DisabledSome,
    OutInfo, OutUnknown,
    DecOk, DecNoDv, DecNoHevc, DecUnknown,
}

data class CheckResult(val id: CheckId, val status: Status, val outcome: Outcome, val value: String)

/**
 * Turns a [ChainSnapshot] into green/yellow/red results. Pure logic, so every rule is unit tested. Sources disagree
 * on this box (Android hides the HDR formats from apps but the HDMI driver sees them), so each rule combines them.
 */
object Checks {

    private const val NONE = "—"

    fun evaluate(s: ChainSnapshot): List<CheckResult> = listOf(
        resolution(s), refreshRates(s), hdr10(s), dolbyVision(s), otherHdr(s), edidLuminance(s),
        matchFrameRate(s), hdrConversion(s), disabledHdr(s), hdmiOutput(s), decoders(s),
    )

    /** HDR formats the sink accepts, from Android and from the HDMI driver. */
    fun sinkHdrTypes(s: ChainSnapshot): Set<HdrType> =
        (s.apiHdrTypes.valueOrNull().orEmpty() + s.hdmiTx.valueOrNull()?.hdrTypes.orEmpty())

    private fun resolution(s: ChainSnapshot): CheckResult {
        val mode = s.apiActiveMode.valueOrNull()
            ?: return CheckResult(CheckId.Resolution, Status.Warn, Outcome.ResUnknown, NONE)
        val outcome = if (mode.is4k()) Outcome.Res4k else Outcome.ResLow
        return CheckResult(CheckId.Resolution, if (mode.is4k()) Status.Pass else Status.Fail, outcome, mode.toString())
    }

    /** 4K modes from whichever source lists more than the current one. */
    fun fourKModes(s: ChainSnapshot): List<DisplayModeInfo> {
        val api = s.apiModes.valueOrNull().orEmpty().filter { it.is4k() }
        val sysfs = s.hdmiTx.valueOrNull()?.modes.orEmpty().filter { it.is4k() }
        return if (api.size >= sysfs.size) api else sysfs
    }

    private fun refreshRates(s: ChainSnapshot): CheckResult {
        val modes = fourKModes(s)
        if (modes.size <= 1) return CheckResult(CheckId.RefreshRates, Status.Warn, Outcome.RatesUnknown, NONE)
        fun has(nominal: Float) = modes.any { abs(it.fps - nominal) < 0.6f }
        val value = modes.map { it.fps }.distinct().sorted().joinToString(", ") { formatFps(it) }
        return when {
            !has(24f) -> CheckResult(CheckId.RefreshRates, Status.Fail, Outcome.RatesNo24, value)
            !has(25f) || !has(50f) -> CheckResult(CheckId.RefreshRates, Status.Warn, Outcome.RatesNoPal, value)
            else -> CheckResult(CheckId.RefreshRates, Status.Pass, Outcome.RatesAll, value)
        }
    }

    private fun hdr10(s: ChainSnapshot): CheckResult =
        if (HdrType.Hdr10 in sinkHdrTypes(s)) CheckResult(CheckId.Hdr10, Status.Pass, Outcome.Hdr10Ok, "HDR10")
        else CheckResult(CheckId.Hdr10, Status.Fail, Outcome.Hdr10Missing, NONE)

    private fun dolbyVision(s: ChainSnapshot): CheckResult {
        val inApi = HdrType.DolbyVision in s.apiHdrTypes.valueOrNull().orEmpty()
        val tx = s.hdmiTx.valueOrNull()
        val inTx = tx != null && HdrType.DolbyVision in tx.hdrTypes
        val value = tx?.dvModes?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: if (inApi) "Dolby Vision" else NONE
        return when {
            inApi -> CheckResult(CheckId.DolbyVision, Status.Pass, Outcome.DvOk, value)
            inTx -> CheckResult(CheckId.DolbyVision, Status.Pass, Outcome.DvOkHidden, value)
            else -> CheckResult(CheckId.DolbyVision, Status.Fail, Outcome.DvMissing, NONE)
        }
    }

    private fun otherHdr(s: ChainSnapshot): CheckResult {
        val types = sinkHdrTypes(s)
        val names = listOfNotNull(
            "HDR10+".takeIf { HdrType.Hdr10Plus in types },
            "HLG".takeIf { HdrType.Hlg in types },
        )
        return CheckResult(CheckId.OtherHdr, Status.Info, Outcome.OtherHdrInfo, names.joinToString(", ").ifEmpty { NONE })
    }

    private fun edidLuminance(s: ChainSnapshot): CheckResult {
        // The HDMI driver shows the raw EDID values (blocked for apps on the Homatics). Android substitutes defaults
        // when the EDID has none: equal peak and average with a zero minimum is that default, not a real TV.
        val raw = s.hdmiTx.valueOrNull()?.luminance
        val lum = raw ?: s.apiLuminance.valueOrNull()
            ?: return CheckResult(CheckId.EdidLuminance, Status.Info, Outcome.LumUnknown, NONE)
        if (lum.isEmpty) return CheckResult(CheckId.EdidLuminance, Status.Warn, Outcome.LumEmpty, "0")
        val value = "${lum.max.toInt()} / ${lum.maxAverage.toInt()} / ${lum.min}"
        if (raw == null && lum.max == lum.maxAverage && lum.min == 0f) {
            return CheckResult(CheckId.EdidLuminance, Status.Info, Outcome.LumDefault, value)
        }
        return CheckResult(CheckId.EdidLuminance, Status.Pass, Outcome.LumOk, value)
    }

    private fun matchFrameRate(s: ChainSnapshot): CheckResult = when (s.matchContent.valueOrNull()) {
        MatchContent.Always -> CheckResult(CheckId.MatchFrameRate, Status.Pass, Outcome.MatchAlways, "Always")
        MatchContent.SeamlessOnly ->
            CheckResult(CheckId.MatchFrameRate, Status.Warn, Outcome.MatchSeamless, "Seamless only")
        MatchContent.Never -> CheckResult(CheckId.MatchFrameRate, Status.Fail, Outcome.MatchNever, "Never")
        null -> CheckResult(CheckId.MatchFrameRate, Status.Warn, Outcome.MatchUnknown, NONE)
    }

    private fun hdrConversion(s: ChainSnapshot): CheckResult = when (s.hdrConversion.valueOrNull()) {
        HdrConversion.Passthrough ->
            CheckResult(CheckId.HdrConversion, Status.Pass, Outcome.ConvPassthrough, "Passthrough")
        HdrConversion.System -> CheckResult(CheckId.HdrConversion, Status.Warn, Outcome.ConvSystem, "System")
        HdrConversion.Force -> CheckResult(CheckId.HdrConversion, Status.Fail, Outcome.ConvForce, "Force")
        HdrConversion.Unsupported, null ->
            CheckResult(CheckId.HdrConversion, Status.Info, Outcome.ConvUnknown, NONE)
    }

    private fun disabledHdr(s: ChainSnapshot): CheckResult {
        val disabled = s.userDisabledHdr.valueOrNull()?.trim().orEmpty()
        if (disabled.isEmpty()) return CheckResult(CheckId.DisabledHdr, Status.Pass, Outcome.DisabledNone, NONE)
        val names = disabled.split(',').mapNotNull { it.trim().toIntOrNull()?.let(HdrType::fromAndroid) }
            .joinToString(", ") { it.displayName }
        return CheckResult(CheckId.DisabledHdr, Status.Fail, Outcome.DisabledSome, names.ifEmpty { disabled })
    }

    private fun hdmiOutput(s: ChainSnapshot): CheckResult {
        val attr = s.hdmiTx.valueOrNull()?.outputAttr
            ?: return CheckResult(CheckId.HdmiOutput, Status.Info, Outcome.OutUnknown, NONE)
        return CheckResult(CheckId.HdmiOutput, Status.Info, Outcome.OutInfo, describeAttr(attr))
    }

    /** "444,8bit" → "YCbCr 4:4:4, 8-bit"; anything unrecognised is shown as it is. */
    fun describeAttr(attr: String): String {
        val parts = attr.lowercase().split(',').map { it.trim() }
        val format = when (parts.firstOrNull()) {
            "444" -> "YCbCr 4:4:4"; "422" -> "YCbCr 4:2:2"; "420" -> "YCbCr 4:2:0"; "rgb" -> "RGB"
            else -> return attr.trim()
        }
        val depth = parts.getOrNull(1)?.removeSuffix("bit")?.toIntOrNull()?.let { "$it-bit" }
        return listOfNotNull(format, depth).joinToString(", ")
    }

    private fun decoders(s: ChainSnapshot): CheckResult {
        val d = s.decoders.valueOrNull() ?: return CheckResult(CheckId.Decoders, Status.Warn, Outcome.DecUnknown, NONE)
        val parts = buildList {
            if (d.hevcMain10) add("HEVC 10-bit")
            if (d.dolbyVisionProfiles.isNotEmpty()) add("DV " + d.dolbyVisionProfiles.sorted().joinToString("/"))
            if (d.hevcHdr10Plus) add("HDR10+")
            if (d.av1Main10) add("AV1 10-bit")
            if (d.vp9Profile2) add("VP9.2")
        }
        val value = parts.joinToString(", ").ifEmpty { NONE }
        return when {
            !d.hevcMain10 || !d.hevc4k60 -> CheckResult(CheckId.Decoders, Status.Fail, Outcome.DecNoHevc, value)
            d.dolbyVisionProfiles.isEmpty() -> CheckResult(CheckId.Decoders, Status.Warn, Outcome.DecNoDv, value)
            else -> CheckResult(CheckId.Decoders, Status.Pass, Outcome.DecOk, value)
        }
    }
}

val HdrType.displayName: String
    get() = when (this) {
        HdrType.DolbyVision -> "Dolby Vision"
        HdrType.Hdr10 -> "HDR10"
        HdrType.Hlg -> "HLG"
        HdrType.Hdr10Plus -> "HDR10+"
    }
