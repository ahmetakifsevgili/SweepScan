package com.sweepscan.request

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sweepscan.R
import com.sweepscan.ui.LocationActivity

/**
 * "Söyle": the phone's speech recogniser listens (Turkish), the code heard opens the search
 * screen. Used for voice requests (the app cannot hear WhatsApp's voice notes itself) and from
 * the microphone on the home screen.
 */
class VoiceRequestActivity : AppCompatActivity() {

    private val listen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val wanted = heard.firstNotNullOfOrNull { RequestParser.parseSpoken(it) }
        if (wanted != null) {
            val request = intent.getLongExtra(EXTRA_REQUEST, 0L)
            if (request != 0L) Requests.resolveVoice(this, request, wanted)
            startActivity(
                Intent(this, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, wanted.digits)
                    .putExtra(LocationActivity.EXTRA_REQUEST, request),
            )
        } else if (heard.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.voice_no_code, heard.first()), Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = listOfNotNull(intent.getStringExtra(EXTRA_FROM), getString(R.string.voice_prompt)).joinToString("\n")
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        try {
            listen.launch(ask)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        /** Who asked and when ("Ayşe · 18:04"), shown while listening. */
        const val EXTRA_FROM = "from"
        /** The voice request this code answers (its id in the queue). */
        const val EXTRA_REQUEST = "request"
    }
}
