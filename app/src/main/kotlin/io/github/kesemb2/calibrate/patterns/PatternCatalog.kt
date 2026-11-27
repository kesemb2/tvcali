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
        "hdr_black_clipping" to PatternText(R.string.p_hdr_black_clipping, R.string.p_hdr_black_clipping_short, R.string.p_hdr_black_clipping_howto),
        "hdr_white_clipping_1000" to PatternText(R.string.p_hdr_white_clipping_1000, R.string.p_hdr_white_clipping_1000_short, R.string.p_hdr_white_clipping_1000_howto),
        "hdr_white_clipping_4000" to PatternText(R.string.p_hdr_white_clipping_4000, R.string.p_hdr_white_clipping_4000_short, R.string.p_hdr_white_clipping_4000_howto),
        "hdr_grayscale" to PatternText(R.string.p_hdr_grayscale, R.string.p_hdr_grayscale_short, R.string.p_hdr_grayscale_howto),
        "hdr_dimming_box" to PatternText(R.string.p_hdr_dimming_box, R.string.p_hdr_dimming_box_short, R.string.p_hdr_dimming_box_howto),
        "hdr_subtitles" to PatternText(R.string.p_hdr_subtitles, R.string.p_hdr_subtitles_short, R.string.p_hdr_subtitles_howto),
        "hdr_starfield" to PatternText(R.string.p_hdr_starfield, R.string.p_hdr_starfield_short, R.string.p_hdr_starfield_howto),
        "hdr_pan_24p" to PatternText(R.string.p_hdr_pan_24p, R.string.p_hdr_pan_24p_short, R.string.p_hdr_pan_24p_howto),
        "sdr_overscan" to PatternText(R.string.p_sdr_overscan, R.string.p_sdr_overscan_short, R.string.p_sdr_overscan_howto),
    )

    fun text(id: String): PatternText? = texts[id]

    fun text(p: PatternFile): PatternText? = texts[p.textId]

    fun formatLabel(format: String): Int? = when (format) {
        PatternFormats.SDR -> R.string.format_sdr
        PatternFormats.HDR10 -> R.string.format_hdr10
        PatternFormats.HDR10_PLUS -> R.string.format_hdr10plus
        PatternFormats.DOLBY_VISION -> R.string.format_dv
        else -> null
    }

    fun tabName(format: String): Int? = when (format) {
        PatternFormats.SDR -> R.string.tab_sdr
        PatternFormats.HDR10 -> R.string.tab_hdr10
        PatternFormats.HDR10_PLUS -> R.string.tab_hdr10plus
        PatternFormats.DOLBY_VISION -> R.string.tab_dv
        else -> null
    }

    fun tabNote(format: String): Int? = when (format) {
        PatternFormats.HDR10_PLUS -> R.string.tab_note_hdr10plus
        PatternFormats.DOLBY_VISION -> R.string.tab_note_dv
        else -> null
    }
}
