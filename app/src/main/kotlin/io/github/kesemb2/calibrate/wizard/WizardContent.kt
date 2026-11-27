package io.github.kesemb2.calibrate.wizard

import io.github.kesemb2.calibrate.patterns.PatternFormats
import org.json.JSONObject

/** Texts a step may override for SDR or for HDR flows. */
data class StepTexts(val what: String?, val correct: String?, val wrong: String?)

data class WizardStep(
    val id: String,
    val title: String,
    val what: String,
    val menu: String,
    val recommended: String,
    val pattern: String?,
    val patternSdr: String?,
    val patternHdr: String?,
    val correct: String,
    val wrong: String,
    /** A TV setting key the app can read over adb to confirm the change (optional). */
    val tvSetting: String?,
    val sdr: StepTexts?,
    val hdr: StepTexts?,
)

data class WizardFlow(
    val id: String,
    val format: String,
    val title: String,
    val subtitle: String,
    val intro: String,
    val startPattern: String,
    val stepIds: List<String>,
) {
    val isSdr: Boolean get() = format == PatternFormats.SDR
}

/** A step as shown inside one flow: texts resolved for its format and the exact pattern file id to play. */
data class ResolvedStep(
    val step: WizardStep,
    val what: String,
    val correct: String,
    val wrong: String,
    val patternId: String,
)

class WizardContent(val version: Int, val steps: Map<String, WizardStep>, val flows: List<WizardFlow>) {

    fun flow(id: String): WizardFlow? = flows.firstOrNull { it.id == id }

    fun stepsOf(flow: WizardFlow): List<ResolvedStep> = flow.stepIds.mapNotNull { steps[it] }.map { resolve(flow, it) }

    fun resolve(flow: WizardFlow, step: WizardStep): ResolvedStep {
        val o = if (flow.isSdr) step.sdr else step.hdr
        return ResolvedStep(
            step = step,
            what = o?.what ?: step.what,
            correct = o?.correct ?: step.correct,
            wrong = o?.wrong ?: step.wrong,
            patternId = patternFor(flow, step),
        )
    }

    companion object {
        /**
         * A flow only plays patterns of its own format: switching format would move the TV to another picture mode
         * and the user would be adjusting the wrong settings.
         */
        fun patternFor(flow: WizardFlow, step: WizardStep): String {
            if (flow.isSdr) {
                return step.patternSdr ?: step.pattern?.takeIf { it.startsWith("sdr_") } ?: flow.startPattern
            }
            val base = step.pattern?.takeIf { it.startsWith("hdr_") } ?: step.patternHdr ?: flow.startPattern
            return forFormat(base, flow.format)
        }

        fun forFormat(hdrId: String, format: String): String {
            val suffix = hdrId.removePrefix("hdr_")
            return when (format) {
                PatternFormats.HDR10_PLUS -> "hdrp_$suffix"
                PatternFormats.DOLBY_VISION -> "dv_$suffix"
                else -> hdrId
            }
        }

        fun parse(json: String): WizardContent {
            val o = JSONObject(json)
            val stepsJson = o.getJSONObject("steps")
            val steps = stepsJson.keys().asSequence().associateWith { id ->
                val s = stepsJson.getJSONObject(id)
                fun texts(key: String) = s.optJSONObject(key)?.let {
                    StepTexts(it.optStringOrNull("what"), it.optStringOrNull("correct"), it.optStringOrNull("wrong"))
                }
                WizardStep(
                    id = id,
                    title = s.getString("title"),
                    what = s.getString("what"),
                    menu = s.optString("menu"),
                    recommended = s.optString("recommended"),
                    pattern = s.optStringOrNull("pattern"),
                    patternSdr = s.optStringOrNull("pattern_sdr"),
                    patternHdr = s.optStringOrNull("pattern_hdr"),
                    correct = s.optString("correct"),
                    wrong = s.optString("wrong"),
                    tvSetting = s.optStringOrNull("tv_setting"),
                    sdr = texts("sdr"),
                    hdr = texts("hdr"),
                )
            }
            val flowsJson = o.getJSONArray("flows")
            val flows = (0 until flowsJson.length()).map { i ->
                val f = flowsJson.getJSONObject(i)
                val ids = f.getJSONArray("steps")
                WizardFlow(
                    id = f.getString("id"),
                    format = f.getString("format"),
                    title = f.getString("title"),
                    subtitle = f.optString("subtitle"),
                    intro = f.optString("intro"),
                    startPattern = f.getString("start_pattern"),
                    stepIds = (0 until ids.length()).map { ids.getString(it) },
                )
            }
            return WizardContent(o.optInt("version", 1), steps, flows)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key).ifEmpty { null } else null
    }
}
