package io.github.kesemb2.calibrate.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.HdrConversionMode
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.Settings
import android.view.Display
import java.io.File

/**
 * Reads every source an ordinary app can reach about the display chain. Each read is isolated: one source failing
 * (a SELinux denial, an API the build hides) only marks that source as unavailable.
 */
object ChainProbe {

    private const val HDMITX = "/sys/class/amhdmitx/amhdmitx0"

    fun collect(context: Context): ChainSnapshot {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display: Display? = dm.getDisplay(Display.DEFAULT_DISPLAY)

        return ChainSnapshot(
            apiActiveMode = read { display!!.mode.toInfo() },
            apiModes = read { display!!.supportedModes.map { it.toInfo() } },
            apiHdrTypes = read { apiHdrTypes(display!!) },
            apiLuminance = read {
                @Suppress("DEPRECATION")
                val caps = display!!.hdrCapabilities ?: error("no HdrCapabilities")
                Luminance(caps.desiredMaxLuminance, caps.desiredMaxAverageLuminance, caps.desiredMinLuminance)
            },
            matchContent = read {
                require(Build.VERSION.SDK_INT >= 31) { "API ${Build.VERSION.SDK_INT}" }
                when (dm.matchContentFrameRateUserPreference) {
                    DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS -> MatchContent.Always
                    DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY -> MatchContent.SeamlessOnly
                    DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> MatchContent.Never
                    else -> error("unknown value")
                }
            },
            hdrConversion = read {
                require(Build.VERSION.SDK_INT >= 34) { "API ${Build.VERSION.SDK_INT}" }
                when (dm.hdrConversionMode.conversionMode) {
                    HdrConversionMode.HDR_CONVERSION_PASSTHROUGH -> HdrConversion.Passthrough
                    HdrConversionMode.HDR_CONVERSION_SYSTEM -> HdrConversion.System
                    HdrConversionMode.HDR_CONVERSION_FORCE -> HdrConversion.Force
                    else -> HdrConversion.Unsupported
                }
            },
            userDisabledHdr = read {
                Settings.Global.getString(context.contentResolver, "user_disabled_hdr_formats").orEmpty()
            },
            hdmiTx = read { hdmiTx() },
            sysfsMode = read { File("/sys/class/display/mode").readText().trim() },
            decoders = read { decoders() },
            sinkName = runCatching {
                if (Build.VERSION.SDK_INT >= 31) display?.deviceProductInfo?.let { "${it.name} (${it.manufacturerPnpId})" }
                else null
            }.getOrNull(),
            build = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}",
        )
    }

    private fun apiHdrTypes(display: Display): Set<HdrType> {
        val fromMode = if (Build.VERSION.SDK_INT >= 34) HdrType.fromAndroid(display.mode.supportedHdrTypes) else null
        @Suppress("DEPRECATION")
        val fromCaps = HdrType.fromAndroid(display.hdrCapabilities?.supportedHdrTypes)
        return fromMode.orEmpty() + fromCaps.orEmpty()
    }

    private fun hdmiTx(): HdmiTxInfo {
        val hdrCap = File("$HDMITX/hdr_cap").readText()
        val (types, luminance) = HdmiTxParser.parseHdrCap(hdrCap)
        val dv = runCatching { HdmiTxParser.parseDvCap(File("$HDMITX/dv_cap").readText()) }.getOrNull()
        return HdmiTxInfo(
            hdrTypes = if (dv != null) types + HdrType.DolbyVision else types,
            luminance = luminance,
            dvModes = dv?.first.orEmpty(),
            dv4k60 = dv?.second == true,
            outputAttr = runCatching { File("$HDMITX/attr").readText().trim() }.getOrNull()?.ifEmpty { null },
            modes = runCatching { HdmiTxParser.parseDispCap(File("$HDMITX/disp_cap").readText()) }.getOrDefault(emptyList()),
        )
    }

    private fun decoders(): DecoderInfo {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun caps(mime: String) = decoders.filter { mime in it.supportedTypes }.map { it.getCapabilitiesForType(mime) }
        fun profiles(mime: String) = caps(mime).flatMap { c -> c.profileLevels.map { it.profile } }.toSet()

        val hevc = profiles(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val hevc4k60 = caps(MediaFormat.MIMETYPE_VIDEO_HEVC).any {
            it.videoCapabilities?.areSizeAndRateSupported(3840, 2160, 60.0) == true
        }
        val dv = profiles(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION).mapNotNull { dolbyVisionProfile(it) }.toSet()
        val av1 = profiles(MediaFormat.MIMETYPE_VIDEO_AV1)
        val vp9 = profiles(MediaFormat.MIMETYPE_VIDEO_VP9)
        return DecoderInfo(
            hevcMain10 = CodecProfileLevel.HEVCProfileMain10 in hevc ||
                CodecProfileLevel.HEVCProfileMain10HDR10 in hevc,
            hevc4k60 = hevc4k60,
            hevcHdr10Plus = CodecProfileLevel.HEVCProfileMain10HDR10Plus in hevc,
            dolbyVisionProfiles = dv,
            av1Main10 = CodecProfileLevel.AV1ProfileMain10 in av1 || CodecProfileLevel.AV1ProfileMain10HDR10 in av1,
            vp9Profile2 = CodecProfileLevel.VP9Profile2 in vp9 || CodecProfileLevel.VP9Profile2HDR in vp9,
        )
    }

    /** Android's Dolby Vision profile flags → the profile numbers people know. */
    private fun dolbyVisionProfile(flag: Int): Int? = when (flag) {
        CodecProfileLevel.DolbyVisionProfileDvavPer -> 0
        CodecProfileLevel.DolbyVisionProfileDvavPen -> 1
        CodecProfileLevel.DolbyVisionProfileDvheDer -> 2
        CodecProfileLevel.DolbyVisionProfileDvheDen -> 3
        CodecProfileLevel.DolbyVisionProfileDvheDtr -> 4
        CodecProfileLevel.DolbyVisionProfileDvheStn -> 5
        CodecProfileLevel.DolbyVisionProfileDvheDth -> 6
        CodecProfileLevel.DolbyVisionProfileDvheDtb -> 7
        CodecProfileLevel.DolbyVisionProfileDvheSt -> 8
        CodecProfileLevel.DolbyVisionProfileDvavSe -> 9
        CodecProfileLevel.DolbyVisionProfileDvav110 -> 10
        else -> null
    }

    private fun Display.Mode.toInfo() = DisplayModeInfo(physicalWidth, physicalHeight, refreshRate)

    private inline fun <T> read(block: () -> T): Read<T> = try {
        Read.Ok(block())
    } catch (e: Throwable) {
        Read.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }
}
