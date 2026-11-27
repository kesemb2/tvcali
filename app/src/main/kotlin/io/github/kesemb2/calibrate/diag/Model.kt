package io.github.kesemb2.calibrate.diag

import kotlin.math.abs

/** HDR formats as Android numbers them (`Display.HdrCapabilities.HDR_TYPE_*`). */
enum class HdrType(val androidId: Int) {
    DolbyVision(1), Hdr10(2), Hlg(3), Hdr10Plus(4);

    companion object {
        fun fromAndroid(id: Int): HdrType? = entries.firstOrNull { it.androidId == id }
        fun fromAndroid(ids: IntArray?): Set<HdrType>? = ids?.toList()?.mapNotNull { fromAndroid(it) }?.toSet()
    }
}

/** One output mode. Refresh rates are compared with a tolerance, so 23.976025 and 23.976 are the same mode. */
data class DisplayModeInfo(val width: Int, val height: Int, val fps: Float) {
    fun is4k(): Boolean = width >= 3840 && height >= 2160
    fun matches(rate: Float): Boolean = abs(fps - rate) < 0.01f
    override fun toString(): String = "${width}×$height @ ${formatFps(fps)}"
}

fun formatFps(fps: Float): String {
    val rounded = Math.round(fps * 1000f) / 1000f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
    else rounded.toString().trimEnd('0').trimEnd('.')
}

/** Luminance values the TV declares in its EDID (HDR static metadata block), in cd/m². */
data class Luminance(val max: Float, val maxAverage: Float, val min: Float) {
    val isEmpty: Boolean get() = max <= 0f && maxAverage <= 0f
}

/** Android TV's "Match content frame rate" (`DisplayManager.getMatchContentFrameRateUserPreference`). */
enum class MatchContent { Never, SeamlessOnly, Always }

/** `HdrConversionMode.HDR_CONVERSION_*`: passthrough sends HDR as the content has it. */
enum class HdrConversion { Unsupported, Passthrough, System, Force }

/** What one source told us, or why it could not be read. */
sealed interface Read<out T> {
    data class Ok<T>(val value: T) : Read<T>
    data class Failed(val reason: String) : Read<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

/** The Amlogic HDMI transmitter's view of the sink (the device right after the box), from sysfs. */
data class HdmiTxInfo(
    val hdrTypes: Set<HdrType>,
    val luminance: Luminance?,
    val dvModes: List<String>,
    val dv4k60: Boolean,
    /** Current output colour format, e.g. "444,8bit". */
    val outputAttr: String?,
    /** Modes the sink offers, from `disp_cap`. */
    val modes: List<DisplayModeInfo>,
)

data class DecoderInfo(
    val hevcMain10: Boolean,
    val hevc4k60: Boolean,
    val hevcHdr10Plus: Boolean,
    /** Dolby Vision profiles with a decoder (5, 7, 8 …). */
    val dolbyVisionProfiles: Set<Int>,
    val av1Main10: Boolean,
    val vp9Profile2: Boolean,
)

/** Everything the box tells an app about its display chain, from every source we can reach. */
data class ChainSnapshot(
    val apiActiveMode: Read<DisplayModeInfo>,
    val apiModes: Read<List<DisplayModeInfo>>,
    val apiHdrTypes: Read<Set<HdrType>>,
    val apiLuminance: Read<Luminance>,
    val matchContent: Read<MatchContent>,
    val hdrConversion: Read<HdrConversion>,
    val userDisabledHdr: Read<String>,
    val hdmiTx: Read<HdmiTxInfo>,
    val sysfsMode: Read<String>,
    val decoders: Read<DecoderInfo>,
    val sinkName: String?,
    val build: String,
)
