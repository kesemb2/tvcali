package io.github.kesemb2.calibrate.patterns

import io.github.kesemb2.calibrate.R

/** The texts the app shows for each pattern id from the manifest. Unknown (newer) ids still play, under their id. */
data class PatternText(val title: Int, val short: Int, val howto: Int)

object PatternCatalog {
    private val texts = mapOf(
        "sdr_pluge" to PatternText(R.string.p_sdr_pluge, R.string.p_sdr_pluge_short, R.string.p_sdr_pluge_howto),
        "sdr_white_clipping" to PatternText(
            R.string.p_sdr_white_clipping, R.string.p_sdr_white_clipping_short, R.string.p_sdr_white_clipping_howto,
        ),
        "sdr_grayscale" to PatternText(R.string.p_sdr_grayscale, R.string.p_sdr_grayscale_short, R.string.p_sdr_grayscale_howto),
        "sdr_color_bars" to PatternText(
            R.string.p_sdr_color_bars, R.string.p_sdr_color_bars_short, R.string.p_sdr_color_bars_howto,
        ),
        "sdr_sharpness" to PatternText(R.string.p_sdr_sharpness, R.string.p_sdr_sharpness_short, R.string.p_sdr_sharpness_howto),
        "sdr_resolution" to PatternText(
            R.string.p_sdr_resolution, R.string.p_sdr_resolution_short, R.string.p_sdr_resolution_howto,
        ),
        "sdr_overscan" to PatternText(R.string.p_sdr_overscan, R.string.p_sdr_overscan_short, R.string.p_sdr_overscan_howto),
    )

    fun text(id: String): PatternText? = texts[id]

    fun formatLabel(format: String): Int? = when (format) {
        "sdr" -> R.string.format_sdr
        "hdr10" -> R.string.format_hdr10
        else -> null
    }
}
