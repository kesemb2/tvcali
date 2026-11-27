package io.github.kesemb2.calibrate.wizard

/** Plain-text summary of one flow, for the QR code and for copying: one line per step. */
object WizardExport {
    fun text(content: WizardContent, flow: WizardFlow, store: WizardStore, statusLabel: (StepStatus?) -> String): String =
        buildString {
            appendLine("Calibrate · ${flow.title}")
            content.stepsOf(flow).forEach { s ->
                val r = store.record(flow.id, s.step.id)
                val value = r?.value?.takeIf { it.isNotBlank() } ?: s.step.recommended
                appendLine("${s.step.title}: $value (${statusLabel(r?.status)})")
            }
        }.trimEnd()
}
