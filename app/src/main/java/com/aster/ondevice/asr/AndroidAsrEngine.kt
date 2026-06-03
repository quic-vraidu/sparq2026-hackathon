package com.aster.ondevice.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidAsrEngine"

// Transient errors worth retrying once (model not ready / service disconnect)
private val RETRIABLE_ERRORS = setOf(
    8,  // ERROR_RECOGNIZER_BUSY
    11, // ERROR_SERVER_DISCONNECTED
)

/**
 * Phase 1 ASR — uses Android's built-in SpeechRecognizer.
 * On Android 10+ with AOSP on-device model: no internet required.
 * Phase 2: replace with QnnAsrEngine running Whisper on NPU.
 */
@Singleton
class AndroidAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : AsrEngine {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val retryHandler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null

    private fun cancelPendingRetry() {
        pendingRetry?.let { retryHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    /**
     * Pre-warm the on-device ASR service so it's already bound when the user taps the mic.
     * Stores in the recognizer field so startListening() automatically destroys it cleanly.
     */
    fun warmUp() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(noOpListener)
            // Hold 800ms for the service to bind, then release (unless startListening replaced it)
            retryHandler.postDelayed({
                if (recognizer === sr) {
                    sr.destroy()
                    recognizer = null
                    Log.i(TAG, "ASR warm-up complete")
                }
            }, 800)
        }
    }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onError("On-device speech recognition is not available on this device.")
            return
        }
        cancelPendingRetry()
        startListeningInternal(onResult, onError, retryLeft = 1)
    }

    private fun startListeningInternal(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        retryLeft: Int,
    ) {
        recognizer?.destroy()
        recognizer = null
        var callbackFired = false  // guard: only handle the first callback per session

        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (callbackFired) return
                callbackFired = true
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                Log.i(TAG, "ASR result: $text")
                onResult(text)
            }
            override fun onError(error: Int) {
                if (callbackFired) return
                callbackFired = true
                listening = false
                Log.e(TAG, "ASR error code $error")
                if (error in RETRIABLE_ERRORS && retryLeft > 0) {
                    Log.i(TAG, "Transient ASR error $error — retrying in 800ms")
                    val r = Runnable { startListeningInternal(onResult, onError, retryLeft - 1) }
                    pendingRetry = r
                    retryHandler.postDelayed(r, 800)
                } else {
                    onError("Speech recognition failed (code $error). Please try again.")
                }
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(type: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        listening = true
        sr.startListening(intent)
        Log.i(TAG, "Listening started")
    }

    override fun stopListening() {
        cancelPendingRetry()
        recognizer?.stopListening()
        listening = false
    }

    override fun isListening() = listening

    override fun destroy() {
        cancelPendingRetry()
        recognizer?.destroy()
        recognizer = null
    }

    private val noOpListener = object : RecognitionListener {
        override fun onResults(p: Bundle?) {}
        override fun onError(error: Int) {}
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buf: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(type: Int, params: Bundle?) {}
    }
}
