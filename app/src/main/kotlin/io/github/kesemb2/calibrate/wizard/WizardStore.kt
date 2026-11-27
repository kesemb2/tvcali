package io.github.kesemb2.calibrate.wizard

import android.content.Context
import org.json.JSONObject

enum class StepStatus { Done, Skipped, NotFound }

data class StepRecord(val status: StepStatus, val value: String, val at: Long)

/**
 * What the user did in each flow, the value chosen per step, and their own menu paths (per step, shared by all
 * flows since the TV's menu is the same). Kept in SharedPreferences as JSON.
 */
class WizardStore(context: Context) {
    private val prefs = context.getSharedPreferences("wizard", Context.MODE_PRIVATE)

    fun record(flowId: String, stepId: String): StepRecord? =
        records().optJSONObject(flowId)?.optJSONObject(stepId)?.let {
            StepRecord(
                status = runCatching { StepStatus.valueOf(it.getString("status")) }.getOrDefault(StepStatus.Skipped),
                value = it.optString("value"),
                at = it.optLong("at"),
            )
        }

    fun save(flowId: String, stepId: String, record: StepRecord) {
        val all = records()
        val flow = all.optJSONObject(flowId) ?: JSONObject().also { all.put(flowId, it) }
        flow.put(stepId, JSONObject().put("status", record.status.name).put("value", record.value).put("at", record.at))
        prefs.edit().putString(KEY_RECORDS, all.toString()).apply()
    }

    /** The value typed earlier for this step in this flow (kept even when the step is redone). */
    fun value(flowId: String, stepId: String): String = record(flowId, stepId)?.value.orEmpty()

    fun menuPath(stepId: String): String? = paths().optString(stepId).ifEmpty { null }

    fun saveMenuPath(stepId: String, path: String) {
        val all = paths()
        if (path.isBlank()) all.remove(stepId) else all.put(stepId, path.trim())
        prefs.edit().putString(KEY_PATHS, all.toString()).apply()
    }

    fun clearFlow(flowId: String) {
        val all = records()
        all.remove(flowId)
        prefs.edit().putString(KEY_RECORDS, all.toString()).apply()
    }

    private fun records() = JSONObject(prefs.getString(KEY_RECORDS, "{}") ?: "{}")
    private fun paths() = JSONObject(prefs.getString(KEY_PATHS, "{}") ?: "{}")

    private companion object {
        const val KEY_RECORDS = "records"
        const val KEY_PATHS = "menu_paths"
    }
}
