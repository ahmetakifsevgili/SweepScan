package com.sweepscan.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sweepscan.R
import com.sweepscan.request.Requests

/**
 * The home-screen widget: how many requests wait (tap = the queue), and the two most common
 * starts in one tap, read a box and search. Updated whenever the queue changes.
 */
class HomeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        /** Redraws every placed widget (cheap; called when the queue changes). */
        fun update(c: Context) {
            val manager = AppWidgetManager.getInstance(c) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(c, HomeWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val v = views(c)
            for (id in ids) manager.updateAppWidget(id, v)
        }

        private fun views(c: Context): RemoteViews {
            val waiting = Requests.recent(c).count { it.status.open }
            val v = RemoteViews(c.packageName, R.layout.widget_home)
            v.setTextViewText(R.id.widget_count, waiting.toString())
            v.setTextViewText(
                R.id.widget_label,
                if (waiting == 0) c.getString(R.string.widget_none) else c.getString(R.string.widget_waiting),
            )
            v.setOnClickPendingIntent(R.id.widget_requests, activity(c, 1, Intent(c, RequestsActivity::class.java)))
            v.setOnClickPendingIntent(R.id.widget_scan, activity(c, 2, MainActivity.intent(c, MainActivity.Task.LOOKUP)))
            v.setOnClickPendingIntent(
                R.id.widget_search,
                activity(c, 3, Intent(c, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_FOCUS, true)),
            )
            return v
        }

        private fun activity(c: Context, code: Int, intent: Intent): PendingIntent = PendingIntent.getActivity(
            c, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
