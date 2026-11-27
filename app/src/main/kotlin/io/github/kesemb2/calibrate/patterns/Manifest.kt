package io.github.kesemb2.calibrate.patterns

import org.json.JSONObject

/** A region whose decoded luma must be exactly [y10] (10-bit code value). */
data class Probe(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val y10: Int)

data class PatternFile(
    val id: String,
    val file: String,
    val size: Long,
    val sha256: String,
    /** "sdr", "hdr10" … */
    val format: String,
    val probes: List<Probe>,
    /** "pattern" (listed for the user) or "ratetest" (clips for the frame-rate switch test). */
    val role: String = "pattern",
    /** Moving pictures: the player asks the display to switch to their frame rate. */
    val motion: Boolean = false,
    val fpsNum: Int = 24000,
    val fpsDen: Int = 1001,
) {
    val fps: Float get() = fpsNum.toFloat() / fpsDen
}

data class Manifest(val version: Int, val width: Int, val height: Int, val patterns: List<PatternFile>) {
    /** What the user browses and plays. */
    val viewable: List<PatternFile> get() = patterns.filter { it.role == "pattern" }
    val rateTests: List<PatternFile> get() = patterns.filter { it.role == "ratetest" }

    companion object {
        fun parse(json: String): Manifest {
            val o = JSONObject(json)
            val list = o.getJSONArray("patterns")
            return Manifest(
                version = o.getInt("version"),
                width = o.getInt("width"),
                height = o.getInt("height"),
                patterns = (0 until list.length()).map { i ->
                    val p = list.getJSONObject(i)
                    val probes = p.optJSONArray("probes")
                    PatternFile(
                        id = p.getString("id"),
                        file = p.getString("file"),
                        size = p.getLong("size"),
                        sha256 = p.getString("sha256"),
                        format = p.getString("format"),
                        probes = if (probes == null) emptyList() else (0 until probes.length()).map { j ->
                            val q = probes.getJSONObject(j)
                            Probe(q.getString("name"), q.getInt("x"), q.getInt("y"), q.getInt("w"), q.getInt("h"), q.getInt("y10"))
                        },
                        role = p.optString("role", "pattern"),
                        motion = p.optBoolean("motion", false),
                        fpsNum = p.optInt("fps_num", 24000),
                        fpsDen = p.optInt("fps_den", 1001),
                    )
                },
            )
        }
    }
}
