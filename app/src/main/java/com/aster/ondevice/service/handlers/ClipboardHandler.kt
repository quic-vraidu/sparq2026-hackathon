package com.aster.ondevice.service.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {
    override fun supportedActions() = listOf("get_clipboard", "set_clipboard")
    override suspend fun handle(command: Command): CommandResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (command.action) {
            "get_clipboard" -> CommandResult.ok(mapOf("text" to (cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")))
            "set_clipboard" -> {
                val text = command.params["text"]?.jsonPrimitive?.content ?: return CommandResult.err("Missing text")
                cm.setPrimaryClip(ClipData.newPlainText("aster", text))
                CommandResult.ok(mapOf("copied" to true))
            }
            else -> CommandResult.err("Unknown: ${command.action}")
        }
    }
}
