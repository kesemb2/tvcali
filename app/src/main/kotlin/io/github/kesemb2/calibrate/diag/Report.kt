package io.github.kesemb2.calibrate.diag

/**
 * The raw values behind the checks, one line per source, in English: shown under "Sources" and written to logcat so
 * a session can be read back with `adb logcat -d -s Calibrate`.
 */
object Report {

    fun sources(s: ChainSnapshot): List<Pair<String, String>> = listOf(
        "Display.getMode" to s.apiActiveMode.show { it.toString() },
        "Display.getSupportedModes" to s.apiModes.show { modes ->
            "${modes.size}: " + modes.joinToString("; ")
        },
        "Display HDR types" to s.apiHdrTypes.show { types -> names(types) },
        "HdrCapabilities luminance" to s.apiLuminance.show { "max ${it.max}, avg ${it.maxAverage}, min ${it.min}" },
        "Match content frame rate" to s.matchContent.show { it.name },
        "HDR conversion mode" to s.hdrConversion.show { it.name },
        "user_disabled_hdr_formats" to s.userDisabledHdr.show { it.ifEmpty { "(empty)" } },
        "amhdmitx HDR" to s.hdmiTx.show { names(it.hdrTypes) },
        "amhdmitx luminance" to s.hdmiTx.show { tx ->
            tx.luminance?.let { "max ${it.max}, avg ${it.maxAverage}, min ${it.min}" } ?: "(none)"
        },
        "amhdmitx Dolby Vision" to s.hdmiTx.show { tx ->
            if (tx.dvModes.isEmpty()) "(none)" else tx.dvModes.joinToString(", ") + if (tx.dv4k60) " · 2160p60" else ""
        },
        "amhdmitx attr" to s.hdmiTx.show { it.outputAttr ?: "(none)" },
        "amhdmitx disp_cap" to s.hdmiTx.show { tx -> "${tx.modes.size}: " + tx.modes.joinToString("; ") },
        "/sys/class/display/mode" to s.sysfsMode.show { it },
        "Sink (EDID name)" to (s.sinkName ?: "—"),
        "Build" to s.build,
    )

    fun text(s: ChainSnapshot, results: List<CheckResult>): String = buildString {
        appendLine("Calibrate chain report")
        results.forEach { appendLine("${it.status.name.uppercase()} ${it.id}: ${it.value} (${it.outcome})") }
        sources(s).forEach { (k, v) -> appendLine("$k = $v") }
    }

    private fun names(types: Set<HdrType>) = types.sortedBy { it.androidId }.joinToString(", ") { it.displayName }
        .ifEmpty { "(none)" }

    private inline fun <T> Read<T>.show(format: (T) -> String): String = when (this) {
        is Read.Ok -> format(value)
        is Read.Failed -> "unavailable — $reason"
    }
}
