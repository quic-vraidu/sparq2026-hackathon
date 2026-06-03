package com.aster.ondevice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidTtsEngine"

/**
 * Phase 1 TTS — uses Android's built-in TextToSpeech. Fully on-device.
 * Phase 2: replace with QnnTtsEngine running a neural TTS model on NPU.
 */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var initialized = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                initialized = true
                Log.i(TAG, "TTS initialized")
                pendingQueue.forEach { (text, cb) -> speakNow(text, cb) }
                pendingQueue.clear()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    override fun speak(text: String, onDone: (() -> Unit)?) {
        if (!initialized) { pendingQueue += text to onDone; return }
        speakNow(text, onDone)
    }

    private fun speakNow(text: String, onDone: (() -> Unit)?) {
        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == utteranceId) onDone() }
                override fun onError(id: String?) {}
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.i(TAG, "Speaking: ${text.take(60)}")
    }

    override fun stop() { tts?.stop() }

    override fun destroy() {
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
