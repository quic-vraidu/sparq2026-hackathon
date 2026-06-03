package com.aster.ondevice.asr

/** Swappable ASR engine interface.
 *  Phase 1: AndroidAsrEngine (Android SpeechRecognizer — on-device, no internet)
 *  Phase 2: QnnAsrEngine (Whisper via QNN NPU)
 */
interface AsrEngine {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun isListening(): Boolean
    fun destroy()
}
