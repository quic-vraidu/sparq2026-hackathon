package com.aster.ondevice.service.handlers

import android.content.Context
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            tts?.language = Locale.getDefault()
        }
    }

    override fun supportedActions() = listOf("speak_tts", "vibrate", "play_audio", "stop_audio")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "speak_tts"  -> speakTts(command)
        "vibrate"    -> vibrate(command)
        "play_audio" -> playAudio(command)
        "stop_audio" -> { player?.stop(); player?.release(); player = null; CommandResult.ok(mapOf("stopped" to true)) }
        else         -> CommandResult.err("Unknown: ${command.action}")
    }

    private fun speakTts(cmd: Command): CommandResult {
        if (!ttsReady) return CommandResult.err("TTS not ready")
        val text = cmd.params["text"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        return CommandResult.ok(mapOf("speaking" to true))
    }

    private fun vibrate(cmd: Command): CommandResult {
        val pattern = cmd.params["pattern"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.longOrNull
        }?.toLongArray() ?: longArrayOf(0, 300)
        val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        return CommandResult.ok(mapOf("vibrating" to true))
    }

    private fun playAudio(cmd: Command): CommandResult {
        val source = cmd.params["source"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing source")
        return try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(source)
                prepare()
                start()
            }
            CommandResult.ok(mapOf("playing" to true))
        } catch (e: Exception) { CommandResult.err("Play failed: ${e.message}") }
    }
}
