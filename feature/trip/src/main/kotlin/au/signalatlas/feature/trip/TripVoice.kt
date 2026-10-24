package au.signalatlas.feature.trip

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Spoken alerts (TextToSpeech, Australian English) while the Settings voice switch is on. */
class TripVoice(
    context: Context,
    private val enabled: () -> Boolean,
    private val createEngine: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = { c, l -> TextToSpeech(c, l) },
) {
    private val context = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(alert: Alert) {
        if (!enabled()) return
        say(phrase(alert))
    }

    @Synchronized
    private fun say(text: String) {
        val engine = tts ?: createEngine(context) { status -> onInit(status) }.also { tts = it }
        if (!ready) {
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trip")
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        val r = engine.setLanguage(Locale.Builder().setLanguage("en").setRegion("AU").build())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) engine.language = Locale.getDefault()
        ready = true
        pending?.let { engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, "trip") }
        pending = null
    }

    @Synchronized
    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }

    companion object {
        /** "Coverage ends in 1.8 kilometres", "Signal dropping fast", "Back in coverage". */
        fun phrase(a: Alert): String = when (a.kind) {
            AlertKind.LEAVING -> a.distanceM?.let { "Coverage ends in ${TripText.spoken(it)}" } ?: "Coverage ends ahead"
            AlertKind.EARLY -> "Signal dropping fast"
            AlertKind.BACK -> "Back in coverage"
        }
    }
}
