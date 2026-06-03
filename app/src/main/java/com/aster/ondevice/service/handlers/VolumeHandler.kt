package com.aster.ondevice.service.handlers

import android.content.Context
import android.media.AudioManager
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VolumeHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("get_volume", "set_volume")

    override suspend fun handle(command: Command): CommandResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (command.action) {
            "get_volume" -> {
                CommandResult.ok(mapOf(
                    "media"        to am.getStreamVolume(AudioManager.STREAM_MUSIC),
                    "ring"         to am.getStreamVolume(AudioManager.STREAM_RING),
                    "notification" to am.getStreamVolume(AudioManager.STREAM_NOTIFICATION),
                    "alarm"        to am.getStreamVolume(AudioManager.STREAM_ALARM),
                    "call"         to am.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
                    "system"       to am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                ))
            }
            "set_volume" -> {
                val stream = when (command.params["stream"]?.jsonPrimitive?.content) {
                    "media"        -> AudioManager.STREAM_MUSIC
                    "ring"         -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm"        -> AudioManager.STREAM_ALARM
                    "call"         -> AudioManager.STREAM_VOICE_CALL
                    else           -> AudioManager.STREAM_SYSTEM
                }
                val mute  = command.params["mute"]?.jsonPrimitive?.booleanOrNull
                val level = command.params["level"]?.jsonPrimitive?.intOrNull
                if (mute != null) am.adjustStreamVolume(stream,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                else if (level != null) am.setStreamVolume(stream,
                    level.coerceIn(0, am.getStreamMaxVolume(stream)), 0)
                CommandResult.ok(mapOf("set" to true))
            }
            else -> CommandResult.err("Unknown: ${command.action}")
        }
    }
}
