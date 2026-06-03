package com.aster.ondevice.tts

/** Swappable TTS engine interface.
 *  Phase 1: AndroidTtsEngine (Android TextToSpeech)
 *  Phase 2: QnnTtsEngine (neural TTS model via QNN NPU)
 */
interface TtsEngine {
    fun speak(text: String, onDone: (() -> Unit)? = null)
    fun stop()
    fun destroy()
}
