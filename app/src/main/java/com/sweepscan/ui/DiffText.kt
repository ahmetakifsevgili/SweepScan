package com.sweepscan.ui

import android.content.Context
import com.sweepscan.R
import com.sweepscan.location.SweepDiff

/** One line about what a sweep changed in its column ("+2 yeni · −1 gitti · 1 değişti"). */
object DiffText {

    fun summary(context: Context, diff: SweepDiff): String {
        if (diff.firstSweep) return context.getString(R.string.diff_first)
        val parts = buildList {
            // Rows are skimmed (one box per model), so only what appeared or went away counts.
            if (diff.added.isNotEmpty()) add(context.getString(R.string.diff_added, diff.added.size))
            if (diff.removed.isNotEmpty()) add(context.getString(R.string.diff_removed, diff.removed.size))
        }
        if (parts.isEmpty()) return context.getString(R.string.diff_none)
        val moved = if (diff.elsewhere.isNotEmpty()) " · " + context.getString(R.string.diff_moved, diff.elsewhere.size) else ""
        return parts.joinToString(" · ") + moved
    }
}
