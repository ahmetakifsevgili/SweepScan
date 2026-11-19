package com.sweepscan.request

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sweepscan.R
import com.sweepscan.ui.LocationActivity

/**
 * WhatsApp → long press a message → Paylaş → SweepScan: the code in the message becomes a request
 * in the queue and its place opens, with Gönderdim / Yok. Works without any special permission, so
 * also in the version installed from a file (which has no notification watcher).
 */
class ShareRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val wanted = RequestParser.parse(text)
        if (wanted.isEmpty()) {
            Toast.makeText(this, R.string.req_add_no_code, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val first = wanted.first()
        // Shared twice: the intake hands back the request already waiting.
        Intake.message(this, Intake.Source.SHARE, getString(R.string.share_sender), text, notify = false) { made ->
            val id = made.firstOrNull { it.digits == first.digits }?.id ?: 0L
            startActivity(
                Intent(this, LocationActivity::class.java)
                    .putExtra(LocationActivity.EXTRA_QUERY, first.digits)
                    .putExtra(LocationActivity.EXTRA_REQUEST, id),
            )
            finish()
        }
    }

    companion object {
        /** True in the full version, which watches the WhatsApp group by itself. */
        fun watcherAvailable(c: Context): Boolean = runCatching {
            c.packageManager.getServiceInfo(ComponentName(c, RequestWatcher::class.java), 0)
            true
        }.getOrDefault(false)
    }
}
