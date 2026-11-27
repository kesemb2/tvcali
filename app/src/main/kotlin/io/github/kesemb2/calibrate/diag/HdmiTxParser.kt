package io.github.kesemb2.calibrate.diag

/**
 * Parses the Amlogic HDMI transmitter's sysfs files (`/sys/class/amhdmitx/amhdmitx0/…`). Their format is plain text
 * for humans and differs a little between kernel versions, so every field is optional.
 */
object HdmiTxParser {

    /** `hdr_cap`: EOTFs and luminance from the sink's HDR static metadata block, plus HDR10+ support. */
    fun parseHdrCap(text: String): Pair<Set<HdrType>, Luminance?> {
        val types = mutableSetOf<HdrType>()
        if (flag(text, "HDR10Plus Supported")) types += HdrType.Hdr10Plus
        if (flag(text, "SMPTE ST 2084")) types += HdrType.Hdr10
        if (flag(text, "Hybrid Log-Gamma")) types += HdrType.Hlg
        val luminance = run {
            val block = text.substringAfter("Luminance Data", missingDelimiterValue = "")
            if (block.isEmpty()) return@run null
            val max = number(block, "Max") ?: return@run null
            Luminance(max = max, maxAverage = number(block, "Avg") ?: 0f, min = number(block, "Min") ?: 0f)
        }
        return types to luminance
    }

    /** `dv_cap`: the sink's Dolby Vision VSVDB, or a line saying it has none. */
    fun parseDvCap(text: String): Pair<List<String>, Boolean>? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        if ("don't support" in lower || "not support" in lower || "no dolby" in lower) return null
        if ("vsvdb" !in lower && "dolbyvision" !in lower.replace(" ", "")) return null
        val modes = Regex("""^\s*((?:DV|LL)_[A-Za-z0-9_]+)""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toList()
        val fourK60 = Regex("""2160p60hz:\s*1""").containsMatchIn(text)
        return modes to fourK60
    }

    /** `disp_cap`: one mode per line, e.g. `2160p60hz*` (the star marks the sink's preferred mode). */
    fun parseDispCap(text: String): List<DisplayModeInfo> =
        Regex("""(\d{3,4})p(\d+(?:\.\d+)?)hz""").findAll(text).mapNotNull { m ->
            val lines = m.groupValues[1].toInt()
            val width = when (lines) {
                2160 -> 3840; 1080 -> 1920; 720 -> 1280; 576 -> 720; 480 -> 720; else -> return@mapNotNull null
            }
            DisplayModeInfo(width, lines, m.groupValues[2].toFloat())
        }.distinct().toList()

    private fun flag(text: String, label: String): Boolean =
        Regex("""${Regex.escape(label)}\s*:\s*(\d)""").find(text)?.groupValues?.get(1) == "1"

    private fun number(text: String, label: String): Float? =
        Regex("""\b${Regex.escape(label)}\s*:\s*(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
}
