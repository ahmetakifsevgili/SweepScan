package com.sweepscan.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.sweepscan.R
import com.sweepscan.SweepScanApp

/**
 * Asks the user to look at DİA and say whether an uncertain barcode made it in.
 * Sending stays paused until the user resumes it.
 */
object UncertainDialog {

    private var showing: AlertDialog? = null

    fun show(activity: Activity, entryId: Long, typed: Int = -1, enterSent: Boolean = false) {
        val app = activity.application as SweepScanApp
        val entry = app.session.find(entryId) ?: return
        if (showing?.isShowing == true) return
        val digits = if (typed >= 0) typed else entry.value.length
        val enter = if (enterSent) activity.getString(R.string.uncertain_enter_sent) else ""
        showing = AlertDialog.Builder(activity)
            .setTitle(R.string.uncertain_title)
            .setMessage(activity.getString(R.string.uncertain_body, entry.value, digits, entry.value.length, enter))
            .setCancelable(false)
            .setPositiveButton(R.string.uncertain_in_dia) { _, _ ->
                app.session.resolve(entryId, inDia = true)
                app.keyboard.resume()
            }
            .setNegativeButton(R.string.uncertain_not_in_dia) { _, _ ->
                app.session.resolve(entryId, inDia = false)
                app.keyboard.enqueue(entryId, entry.value)
                app.keyboard.resume()
            }
            .setNeutralButton(R.string.uncertain_later, null)
            .show()
    }
}
