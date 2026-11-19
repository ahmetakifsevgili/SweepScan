package com.sweepscan.request

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sweepscan.R

/** "Gönderdim" / "Yok" pressed on a request notification. */
class RequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Requests.ACTION_STATUS) return
        val id = intent.getLongExtra(Requests.EXTRA_ID, 0L)
        val status = runCatching { Requests.Status.valueOf(intent.getStringExtra(Requests.EXTRA_STATUS).orEmpty()) }.getOrNull() ?: return
        val request = Requests.recent(context).firstOrNull { it.id == id }
        val replied = Requests.setStatus(context, id, status)
        if (!replied && Requests.replies(context) && request != null) {
            Requests.copyReply(context, Requests.replyText(context, request, status))
            Toast.makeText(context, R.string.reply_copied, Toast.LENGTH_LONG).show()
        }
    }
}
