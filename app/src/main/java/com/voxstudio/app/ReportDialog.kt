package com.voxstudio.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.widget.*

/** Lets people flag generated audio without leaving the app; see [Reports] for what is sent. */
object ReportDialog {
    fun show(activity: Activity, text: String, voiceKind: String) {
        val ui = Ui(activity)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        val choices = RadioGroup(activity)
        Reports.reasons.forEachIndexed { index, reason ->
            choices.addView(RadioButton(activity).apply { id = index + 1; this.text = reason; textSize = 16f })
        }
        box.addView(choices)
        val note = EditText(activity).apply {
            hint = "Add details (optional)"; textSize = 16f; minLines = 2; maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(Reports.NOTE_LIMIT)); contentDescription = "Report details, optional"
        }
        box.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        box.addView(ui.footnote("Sends your reason, your note and the text of this narration to the developer. No audio, recordings or account details are sent.").apply {
            setPadding(0, ui.dp(10), 0, 0)
        })
        val dialog = AlertDialog.Builder(activity).setTitle("Report This Audio").setView(ScrollView(activity).apply { addView(box) })
            .setNegativeButton("Cancel", null).setPositiveButton("Send", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val picked = choices.checkedRadioButtonId
                if (picked < 1) { Toast.makeText(activity, "Choose a reason first.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                try {
                    Reports.submit(activity, Reports.Report(Reports.reasons[picked - 1], note.text.toString(), text, voiceKind))
                    dialog.dismiss()
                    Toast.makeText(activity, if (Reports.configured) "Thanks. Your report will be sent the next time you're online."
                        else "Thanks. Your report is saved on this phone.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, e.message ?: "Could not save the report.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
